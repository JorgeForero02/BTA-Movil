package net.minecraft.client.render.terrain;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.betterthanadventure.btamobile.meshfix.Mesher;
import net.minecraft.client.render.TileEntityRenderDispatcher;
import net.minecraft.client.render.block.model.BlockModel;
import net.minecraft.client.render.block.model.BlockModelDispatcher;
import net.minecraft.client.render.block.model.BlockModelFluid;
import net.minecraft.client.render.camera.ICamera;
import net.minecraft.client.render.tessellator.TessellatorTerrainImpl;
import net.minecraft.core.block.Block;
import net.minecraft.core.block.entity.TileEntity;
import net.minecraft.core.world.World;
import net.minecraft.core.world.chunk.Chunk;
import net.minecraft.core.world.chunk.ChunkCache;
import net.minecraft.core.world.chunk.SnapshotChunkCache;
import net.minecraft.core.world.pos.TilePos;

/**
 * Drop-in replacement for ChunkRendererMultiDraw whose geometry is built on the mesher worker.
 *
 * Flow (all bookkeeping on the main thread):
 *  - RenderGlobal.updateDirtyChunks calls rebuild(): we take a ChunkCache snapshot and queue a Job.
 *  - The worker meshes into its own tessellator and copies the bytes out (into a pooled array, BytePool).
 *  - At the start of the next updateDirtyChunks, drainCompleted() uploads finished jobs, marks the
 *    chunk clean and removes it from RenderGlobal.dirtyChunks.
 *  - Block changes while a job runs bump {@link #generation} (RenderGlobal.setDirty is patched to
 *    call markIfMeshing); the finished result is still shown but the chunk stays dirty and is meshed again.
 *  - A chunk whose job failed 3 times in a row is rebuilt synchronously by the original code.
 *  - Sections changed by the player's own break/place (PlayerEdit) are meshed right here on the main
 *    thread and uploaded in the same frame, all of them together: vanilla 1.18+'s default
 *    "Semi Blocking" chunk builder (RenderSection.isDirtyFromPlayer -> compileSync).
 */
public class AsyncChunkRenderer extends ChunkRendererMultiDraw {
  private static final TessellatorTerrainImpl UPLOAD = new TessellatorTerrainImpl(0x200000);
  private static final ThreadLocal<TessellatorTerrainImpl> WORKER_TESS =
      ThreadLocal.withInitial(() -> new TessellatorTerrainImpl(0x200000));
  private static final int MAX_FAILS = 3;
  private static final long DRAIN_BUDGET_NS = 4_000_000L;
  /** Player-changed sections rebuilt synchronously per frame (one block change touches up to 8). */
  private static final int SYNC_PER_FRAME = 8;
  private static final long STUCK_NS = 15_000_000_000L;
  private static int syncThisFrame;
  private static Object lastWorld;
  private static boolean warnedStuck;
  private static final boolean[] stuckWorker = new boolean[4]; // by worker index: its job was given up after 15 s
  private static long lastFrameId = -1, lastDrain, watchdogFrom;

  /** True if every running worker is stuck in a job (main thread). */
  private static boolean allWorkersStuck() {
    int workerCount = Mesher.threads();
    for (int i = 0; i < workerCount && i < stuckWorker.length; i++) if (!stuckWorker[i]) return false;
    return true;
  }

  private final TerrainRendererMultiDraw terrain;
  private int generation;   // main thread only
  private Job job;          // main thread only
  private int failCount;    // main thread only
  private boolean climateFilled; // main thread only: missing biome data was computed once for this position
  /** Face connectivity for cave culling (VisGraph format). Unknown = everything connected (conservative). */
  long visData = net.betterthanadventure.btamobile.meshfix.VisGraph.ALL;
  /** Last BFS token in which this section was reached (CaveCuller). */
  int reachedToken;
  private final org.joml.primitives.AABBd cullBox = new org.joml.primitives.AABBd();
  private int boxX = Integer.MIN_VALUE, boxY, boxZ;
  private long hiddenSince; // waiting for the chunk to be decorated (trees, ores) before showing it
  private boolean waitingDeco; // currently waiting: kept out of the per-frame rebuild slots
  private boolean fromPlayer;  // dirtied by the player's own block action: rebuild in sync
  private long retryAt;        // fallback failed: retry the section after this time (0 = none)
  private long lastWaitCheck;

  public AsyncChunkRenderer(TerrainRendererMultiDraw terrainRenderer, World world, List<TileEntity> globalTileEntities, int x, int y, int z, int size) {
    super(terrainRenderer, world, globalTileEntities, x, y, z, size);
    this.terrain = terrainRenderer;
  }

  // ---------------------------------------------------------------- hooks used by RenderGlobal

  /** Replaces ChunkRenderer.isDirty() in RenderGlobal.setDirty/allChanged. */
  public static boolean markIfMeshing(ChunkRenderer section) {
    boolean dirty = section.isDirty();
    if (dirty && section instanceof AsyncChunkRenderer) {
      AsyncChunkRenderer asyncSection = (AsyncChunkRenderer) section;
      if (asyncSection.job != null) asyncSection.generation++; // content changed while meshing: mesh again afterwards
      asyncSection.notePlayer(); // already dirty (not set again by RenderGlobal): may still be the player's change
    }
    return dirty;
  }

  private void notePlayer() {
    if (!fromPlayer && Mesher.enabled() && net.betterthanadventure.btamobile.meshfix.PlayerEdit.enabled()
        && net.betterthanadventure.btamobile.meshfix.PlayerEdit.affects(pos.x, pos.y, pos.z, size.x, size.y, size.z)) fromPlayer = true;
  }

  /** Replaces distanceToCameraSquared() in updateDirtyChunks' selection loop: chunks already being
   *  meshed go to the cheap "near" list so they do not take one of the per-frame rebuild slots.
   *  (Uses RenderGlobal's near threshold, patched from 2048 to 576 dist^2; -1 is always below it.) */
  public static float selectDistance(ChunkRenderer section, ICamera camera) {
    if (section instanceof AsyncChunkRenderer) {
      AsyncChunkRenderer asyncSection = (AsyncChunkRenderer) section;
      if (asyncSection.fromPlayer) return -1.0f; // "near" list: rebuilt this frame, whatever the slots
      if (asyncSection.job != null || asyncSection.waitingDeco || asyncSection.retryAt != 0) return -1.0f; // cheap check every frame, no rebuild slot used
    }
    return section.distanceToCameraSquared(camera);
  }

  /** RenderGlobal.updateDirtyChunks' "near" threshold (dist^2), replacing BTA's 2048: 576 with async
   *  meshing (the near list is cheap: only queued, see selectDistance), BTA's own 2048 without it. */
  public static int nearThreshold() { return Mesher.enabled() ? 576 : 2048; }

  /** Called at the start of RenderGlobal.updateDirtyChunks with its dirtyChunks list. */
  public static void drainCompleted(List<ChunkRenderer> dirtyChunks) {
    // WorldRenderer calls updateDirtyChunks in a loop while frame time is left (FPS limit, "load chunks
    // quickly" key): per-frame work runs only on the first call of each rendered frame.
    long frameId = net.betterthanadventure.btamobile.meshfix.Profiler.frameId();
    boolean newFrame = frameId != lastFrameId;
    lastFrameId = frameId;
    if (newFrame) net.betterthanadventure.btamobile.zalithfix.BufferUpload.frame(); // fence last frame's freed VBO space, give back completed space
    if (!Mesher.enabled()) {
      // synchronous meshing: nothing to drain, but the console recipe list is still built in the background
      Object currentWorld = null;
      try { currentWorld = net.minecraft.client.Minecraft.getMinecraft().currentWorld; } catch (Throwable ignored) { }
      if (currentWorld != lastWorld) {
        lastWorld = currentWorld;
        if (currentWorld != null) net.minecraft.client.gui.container.LegacyRecipes.warmUp();
      }
      return;
    }
    long now = System.nanoTime();
    if (now - lastDrain > 1_000_000_000L) watchdogFrom = now; // no frames for a while (app in background): restart the clocks
    lastDrain = now;
    if (newFrame) syncThisFrame = 0;
    worldCheck();
    net.betterthanadventure.btamobile.meshfix.Profiler.enter(net.betterthanadventure.btamobile.meshfix.Profiler.DRAIN);
    try { drain(dirtyChunks); } finally { net.betterthanadventure.btamobile.meshfix.Profiler.exit(net.betterthanadventure.btamobile.meshfix.Profiler.DRAIN); }
  }

  /** Another world (or none): drop what belonged to the old one. */
  private static void worldCheck() {
    Object currentWorld = null;
    try { currentWorld = net.minecraft.client.Minecraft.getMinecraft().currentWorld; } catch (Throwable ignored) { }
    if (currentWorld == lastWorld) return;
    lastWorld = currentWorld;
    net.betterthanadventure.btamobile.meshfix.PlayerEdit.clear();
    net.betterthanadventure.btamobile.meshfix.BytePool.clear();
    net.betterthanadventure.btamobile.meshfix.FluidContact.clear();
    // console crafting: build the recipe list in the background now, not when the menu first opens
    if (currentWorld != null) net.minecraft.client.gui.container.LegacyRecipes.warmUp();
  }

  private static void drain(List<ChunkRenderer> dirtyChunks) {
    long start = System.nanoTime();
    Mesher.Task task;
    while ((task = Mesher.COMPLETED.poll()) != null) {
      Job meshJob = (Job) task;
      AsyncChunkRenderer renderer = meshJob.owner;
      if (meshJob.stuck && meshJob.workerIndex >= 0 && meshJob.workerIndex < stuckWorker.length) stuckWorker[meshJob.workerIndex] = false; // that worker came back after all
      if (renderer.job == meshJob) {
        try { renderer.finish(meshJob, dirtyChunks); }
        catch (Throwable error) { // never let one section's upload break the frame: it stays dirty and is meshed again
          Mesher.reportError(error);
          renderer.failCount++;
        }
      }
      meshJob.releaseBytes(); // geometry already uploaded (or dropped): back to the pool
      if (System.nanoTime() - start > DRAIN_BUDGET_NS) break;
    }
  }

  // ---------------------------------------------------------------- ChunkRenderer overrides

  @Override
  public void setDirty(boolean dirty) {
    super.setDirty(dirty);
    if (dirty) { generation++; notePlayer(); }
    else fromPlayer = false;
  }

  @Override
  public void reset() {
    if (job != null) job.cancelled = true;
    job = null;
    generation++;
    failCount = 0;
    climateFilled = false;
    hiddenSince = 0;
    waitingDeco = false;
    fromPlayer = false;
    retryAt = 0;
    visData = net.betterthanadventure.btamobile.meshfix.VisGraph.ALL;
    CaveCuller.markDirty();
    super.reset();
  }

  @Override
  public boolean rebuild(boolean priority) {
    if (!Mesher.enabled()) return super.rebuild(priority);
    if (!isDirty()) return false;
    if (fromPlayer && failCount < MAX_FAILS && syncThisFrame < SYNC_PER_FRAME) {
      Boolean done = rebuildNow();
      if (done != null) return done;
    }
    if (job != null) {
      long since = job.runSince;
      long now = System.nanoTime();
      if (since != 0 && !job.done && now - Math.max(since, watchdogFrom) > STUCK_NS) { // a worker started it and never finished: give up on it
        job.cancelled = true;
        job.stuck = true;
        int stuckIndex = job.workerIndex;
        if (stuckIndex >= 0 && stuckIndex < stuckWorker.length) stuckWorker[stuckIndex] = true;
        job = null;
        if (!warnedStuck) { warnedStuck = true; System.out.println("[BTA-mesh] WARNING: a chunk took over 15 s to build; it is built again"); }
      } else if (since == 0 && allWorkersStuck() && now - Math.max(job.queuedAt, watchdogFrom) > STUCK_NS) {
        // every worker is stuck: this job will never start. BTA's own code builds the section here.
        job.cancelled = true;
        job = null;
        failCount = MAX_FAILS;
      }
      return false; // still meshing; drainCompleted will finish it
    }
    if (retryAt != 0) {
      if (System.nanoTime() - retryAt < 0) return false;
      retryAt = 0;
    }

    // Do not show bare terrain: wait (up to 20 s) until BTA has decorated this chunk column.
    if (waitingDeco && System.nanoTime() - lastWaitCheck < 200_000_000L) return false; // re-check 5x per second
    lastWaitCheck = System.nanoTime();
    if (net.minecraft.core.world.chunk.provider.MobileWorldgen.waitingForDecoration(getWorld(), pos.x >> 4, pos.z >> 4)) {
      long now = System.nanoTime();
      if (hiddenSince == 0) hiddenSince = now;
      if (now - hiddenSince < 20_000_000_000L) { waitingDeco = true; return false; }
    } else {
      hiddenSince = 0;
    }
    if (waitingDeco) {
      waitingDeco = false;
      // It came through BTA's "near" list only because it was waiting. A far one goes back to the normal
      // per-frame rebuild slots next frame instead of all finished columns rebuilding in the same frame.
      if (farFromPlayer()) return false;
    }

    if (failCount >= MAX_FAILS) {
      // Meshing keeps failing here: use BTA's own code on this thread, but only while the worker
      // is idle (block models share scratch state), and never let it crash the game.
      // (if every worker is stuck it would wait forever: workers never touch the main thread's block
      //  model state, see MesherRenderBlocks, so it is safe to go ahead then)
      if (Mesher.inFlight() > 0 && !allWorkersStuck()) return false;
      try {
        boolean ok = super.rebuild(priority);
        if (ok) { failCount = 0; visData = net.betterthanadventure.btamobile.meshfix.VisGraph.ALL; CaveCuller.markDirty(); }
        return ok;
      } catch (Throwable error) {
        Mesher.reportError(error);
        resetSyncTessellator();
        failCount = 0; // give the worker another chance...
        retryAt = System.nanoTime() + 2_000_000_000L; // ...in 2 s (meanwhile on the cheap list, no slot used)
        if (retryAt == 0) retryAt = 1;
        return false;
      }
    }
    if (!Mesher.canAccept(priority)) return false; // queue full: do not build the snapshot for nothing

    int x0 = pos.x, y0 = pos.y, z0 = pos.z;
    int x1 = x0 + size.x, y1 = y0 + size.y, z1 = z0 + size.z;
    net.betterthanadventure.btamobile.meshfix.Profiler.enter(net.betterthanadventure.btamobile.meshfix.Profiler.SNAPSHOT);
    SnapshotChunkCache cache;
    try {
      cache = new SnapshotChunkCache(getWorld(), new TilePos(x0 - 1, y0 - 1, z0 - 1), new TilePos(x1 + 1, y1 + 1, z1 + 1), priority);
    } finally { net.betterthanadventure.btamobile.meshfix.Profiler.exit(net.betterthanadventure.btamobile.meshfix.Profiler.SNAPSHOT); }
    if (!cache.allLoaded()) return false;
    RenderRegion<?> region = terrain.getRenderRegionAtBlockPos(x0, z0); // region map is main-thread only
    Job newJob = new Job(this, cache, generation, x0, y0, z0, x1, y1, z1, -(region.posX << 9), -(region.posZ << 9));
    if (!Mesher.submit(newJob, priority)) return false;
    Chunk.isLit = false;
    job = newJob;
    net.betterthanadventure.btamobile.meshfix.Profiler.submitted++;
    return false;
  }

  /**
   * Player-changed section: mesh it now, on this thread, and upload it in this frame (vanilla's
   * compileSync for sections dirty from the player). Same Job code as the workers (thread-local block
   * renderers and tessellator, so it can run while the workers mesh other sections).
   * Returns true/false for RenderGlobal (true = clean now), or null to go the normal async way.
   */
  private Boolean rebuildNow() {
    long startNanos = System.nanoTime();
    try { return rebuildNowTimed(); }
    finally { net.betterthanadventure.btamobile.meshfix.Profiler.syncEdit(System.nanoTime() - startNanos); }
  }

  private Boolean rebuildNowTimed() {
    int x0 = pos.x, y0 = pos.y, z0 = pos.z;
    int x1 = x0 + size.x, y1 = y0 + size.y, z1 = z0 + size.z;
    SnapshotChunkCache cache;
    try {
      cache = new SnapshotChunkCache(getWorld(), new TilePos(x0 - 1, y0 - 1, z0 - 1), new TilePos(x1 + 1, y1 + 1, z1 + 1), true);
    } catch (Throwable error) { Mesher.reportError(error); fromPlayer = false; return null; }
    if (!cache.allLoaded()) { fromPlayer = false; return null; }
    syncThisFrame++;
    fromPlayer = false;
    if (job != null) { job.cancelled = true; job = null; } // outdated: its result is dropped when it comes back
    waitingDeco = false;
    hiddenSince = 0;
    RenderRegion<?> region = terrain.getRenderRegionAtBlockPos(x0, z0);
    Job newJob = new Job(this, cache, generation, x0, y0, z0, x1, y1, z1, -(region.posX << 9), -(region.posZ << 9));
    newJob.run();
    try {
      if (newJob.failed) { failCount++; return false; } // stays dirty: async / fallback path next frame
      boolean clean = complete(newJob);
      Chunk.isLit = false;
      if (clean) setDirty(false);
      return clean;
    } catch (Throwable error) {
      Mesher.reportError(error);
      failCount++;
      return false;
    } finally {
      newJob.cache = null;
      newJob.releaseBytes();
    }
  }

  private boolean farFromPlayer() {
    try {
      net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getMinecraft();
      if (minecraft.thePlayer == null) return false;
      double dx = minecraft.thePlayer.x - (pos.x + 8), dz = minecraft.thePlayer.z - (pos.z + 8);
      return dx * dx + dz * dz > 48.0 * 48.0;
    } catch (Throwable ignored) { return false; }
  }

  // ---------------------------------------------------------------- cave culling (main thread)

  /** Frustum test with the same padded box BTA uses (6 blocks around the section). */
  boolean inFrustum(net.minecraft.client.render.culling.CameraFrustum frustum, float partialTicks) {
    if (boxX != pos.x || boxY != pos.y || boxZ != pos.z) {
      boxX = pos.x; boxY = pos.y; boxZ = pos.z;
      cullBox.setMin(pos.x - 6.0, pos.y - 6.0, pos.z - 6.0);
      cullBox.setMax(pos.x + size.x + 6.0, pos.y + size.y + 6.0, pos.z + size.z + 6.0);
    }
    return frustum.isVisible(cullBox, partialTicks);
  }

  /** Sets the visible flag and tells the render lists when it changes (same as ChunkRendererMultiDraw.cull). */
  void setCullVisible(boolean cullVisible) {
    if (visible != cullVisible) {
      visible = cullVisible;
      terrain.chunkVisibilityChanged(this);
    }
  }

  int sectionX() { return pos.x >> 4; }
  int sectionY() { return pos.y >> 4; }
  int sectionZ() { return pos.z >> 4; }

  // ---------------------------------------------------------------- main thread completion

  private void finish(Job meshJob, List<ChunkRenderer> dirtyChunks) {
    job = null;
    if (meshJob.cancelled) { meshJob.cache = null; return; }
    if (meshJob.failed) { meshJob.cache = null; failCount++; return; } // stays dirty; rebuild() resubmits or falls back
    failCount = 0;
    if (complete(meshJob)) {
      setDirty(false);
      dirtyChunks.removeIf(section -> section == this);
    }
    // otherwise the chunk changed meanwhile: keep it dirty, it gets meshed again
  }

  /** Shows a finished job (main thread). Returns true if the section is now up to date. */
  private boolean complete(Job meshJob) {
    apply(meshJob);
    net.betterthanadventure.btamobile.meshfix.Profiler.uploaded++;
    if (meshJob.visData != visData) { visData = meshJob.visData; CaveCuller.markDirty(); }
    SnapshotChunkCache cache = meshJob.cache;
    meshJob.cache = null; // do not keep chunks alive through finished jobs
    if (cache.missingClimate && !climateFilled) {
      // Some columns had no cached biome/climate (old saves): compute them here on the main thread
      // and mesh once more with the real values. The current result is shown meanwhile.
      climateFilled = true;
      try { cache.fillClimate(meshJob.x0 - 1, meshJob.y0 - 1, meshJob.z0 - 1, meshJob.x1 + 1, meshJob.y1 + 1, meshJob.z1 + 1); }
      catch (Throwable error) { Mesher.reportError(error); }
      return false;
    }
    return meshJob.generation == generation;
  }

  private static java.lang.reflect.Field syncTess;
  private static void resetSyncTessellator() {
    try {
      if (syncTess == null) {
        java.lang.reflect.Field field = ChunkRendererMultiDraw.class.getDeclaredField("TESSELLATOR");
        field.setAccessible(true);
        syncTess = field;
      }
      TessellatorTerrainImpl tessellator = (TessellatorTerrainImpl) syncTess.get(null);
      tessellator.drawing = false;
      tessellator.setTranslation(0.0, 0.0, 0.0);
    } catch (Throwable ignored) {
    }
  }

  /** Mirrors ChunkRendererMultiDraw.rebuild's bookkeeping with the worker's geometry. */
  private void apply(Job meshJob) {
    terrain.chunkDeleted(this);
    ++updates;
    for (int pass = 0; pass < 2; pass++) {
      if (!meshJob.started[pass]) continue;
      byte[] bytes = meshJob.bytes[pass];
      int length = meshJob.length[pass];
      TessellatorTerrainImpl.TerrainVertexData data = UPLOAD.data;
      data.getBuffer().clear();
      if (data.getBuffer().capacity() < length) data.resizeIfNeeded(length);
      if (data.getBuffer().capacity() < length) throw new IllegalStateException("upload buffer did not grow");
      ByteBuffer byteBuffer = data.getBuffer();
      byteBuffer.clear();
      byteBuffer.put(bytes, 0, length);
      data.vertexCount = meshJob.vertexCount[pass];
      terrain.chunkRendered(this, UPLOAD, pass);
      if (pass == 1) net.betterthanadventure.btamobile.meshfix.FluidContact.uploaded(terrain, renderListEntries[1], meshJob.contactVerts, meshJob.vertexCount[1]);
      if (meshJob.rendered[pass]) emptyBitSet = (byte) (emptyBitSet & ~(1 << pass));
    }
    boolean stale = meshJob.generation != generation;
    // Tile entities are looked up here, on the main thread: Chunk.getTileEntity may create them.
    // For an outdated result only positions that still hold a tile-entity block are used (the block may
    // have been broken meanwhile), so a section that keeps changing still gets its chests, signs...
    HashSet<TileEntity> lastSpecial = new HashSet<>(renderableBlockEntities);
    renderableBlockEntities.clear();
    TilePos tilePos = new TilePos(0, 0, 0);
    int[] positions = meshJob.tilePositions;
    for (int i = 0; i + 2 < meshJob.tileCount * 3; i += 3) {
      tilePos.x = positions[i]; tilePos.y = positions[i + 1]; tilePos.z = positions[i + 2];
      if (stale) {
        Block<?> currentBlock = getWorld().getBlockType(tilePos); // the live world: the snapshot would always agree with itself
        if (currentBlock == null || !currentBlock.isEntityTile()) continue;
      }
      TileEntity tileEntity = meshJob.cache.getTileEntity(tilePos);
      if (tileEntity != null && TileEntityRenderDispatcher.instance.hasRenderer(tileEntity)) renderableBlockEntities.add(tileEntity);
    }
    HashSet<TileEntity> newSpecial = new HashSet<>(renderableBlockEntities);
    newSpecial.removeAll(lastSpecial);
    globalRenderableBlockEntities.addAll(newSpecial);
    renderableBlockEntities.forEach(lastSpecial::remove);
    globalRenderableBlockEntities.removeAll(lastSpecial);
    compiled = true;
  }

  // ---------------------------------------------------------------- worker

  /** Only touches the ChunkCache snapshot, block models (thread-local RenderBlocks) and its own tessellator. */
  private static final class Job implements Mesher.Task {
    final AsyncChunkRenderer owner;
    SnapshotChunkCache cache; // cleared by the main thread once finished
    final int generation, x0, y0, z0, x1, y1, z1;
    final double translateX, translateZ;
    final boolean[] started = new boolean[2], rendered = new boolean[2];
    final byte[][] bytes = new byte[2][]; // from BytePool: longer than the data, see length[]
    final int[] length = new int[2];
    final int[] vertexCount = new int[2];
    /** Translucent geometry: how many of its first vertices are water contact faces (meshfix.FluidContact). */
    int contactVerts;
    /** The section has water: only then is the contact sweep worth doing. */
    boolean hasWater;
    int[] tilePositions = new int[48];
    final long[] opaque = new long[64];
    long visData = net.betterthanadventure.btamobile.meshfix.VisGraph.ALL;
    int tileCount;
    volatile boolean cancelled;
    boolean failed; // published to the main thread through Mesher.COMPLETED
    volatile int workerIndex = -1; // which worker runs it (-1: the main thread or not started)
    volatile long runSince; // when a thread began meshing it (stuck-worker check; 0 = still queued)
    boolean stuck;          // main thread: given up by the watchdog while running
    long queuedAt = System.nanoTime(); // main thread: when it was created / queued
    volatile boolean done;  // finished meshing (result may wait in Mesher.COMPLETED, e.g. while the app is in the background)

    Job(AsyncChunkRenderer owner, SnapshotChunkCache cache, int generation, int x0, int y0, int z0, int x1, int y1, int z1, double translateX, double translateZ) {
      this.owner = owner; this.cache = cache; this.generation = generation;
      this.x0 = x0; this.y0 = y0; this.z0 = z0; this.x1 = x1; this.y1 = y1; this.z1 = z1;
      this.translateX = translateX; this.translateZ = translateZ;
    }

    private void addTile(int x, int y, int z) {
      if ((tileCount + 1) * 3 > tilePositions.length) tilePositions = java.util.Arrays.copyOf(tilePositions, tilePositions.length * 2);
      int i = tileCount * 3;
      tilePositions[i] = x; tilePositions[i + 1] = y; tilePositions[i + 2] = z;
      tileCount++;
    }

    /** Main thread, after finish(): returns the geometry arrays to the pool. */
    void releaseBytes() {
      for (int i = 0; i < 2; i++) { net.betterthanadventure.btamobile.meshfix.BytePool.give(bytes[i]); bytes[i] = null; length[i] = 0; }
    }

    @Override
    public void run() {
      long startNanos = System.nanoTime();
      Thread thread = Thread.currentThread();
      if (thread instanceof Mesher.WorkerThread) workerIndex = ((Mesher.WorkerThread) thread).index;
      runSince = startNanos == 0 ? 1 : startNanos;
      try { mesh(); } finally { done = true; }
    }

    private void mesh() {
      contactVerts = 0; // (a job is meshed once; explicit anyway)
      hasWater = false;
      if (cancelled) return;
      TessellatorTerrainImpl tess = WORKER_TESS.get();
      try {
        TilePos blockPos = new TilePos(0, 0, 0);
        BlockModelDispatcher dispatcher = BlockModelDispatcher.getInstance();
        for (int pass = 0; pass < 2; pass++) {
          boolean needsMore = false, anyRendered = false, startedDrawing = false;
          if (pass == 1 && hasWater && net.betterthanadventure.btamobile.meshfix.FluidContact.ON) {
            // water contact faces first, then everything else (meshfix.FluidContact)
            tess.startDrawingQuads();
            tess.setTranslation(translateX, 0.0, translateZ);
            startedDrawing = true;
            net.betterthanadventure.btamobile.meshfix.FluidContact.mode(net.betterthanadventure.btamobile.meshfix.FluidContact.ONLY_CONTACT);
            try {
              for (blockPos.y = y0; blockPos.y < y1; blockPos.y++) {
                if (cancelled) return;
                for (blockPos.z = z0; blockPos.z < z1; blockPos.z++) for (blockPos.x = x0; blockPos.x < x1; blockPos.x++) {
                  Block<?> block = cache.getBlockType(blockPos);
                  if (block == null || block.getMaterial() != net.minecraft.core.block.material.Materials.WATER) continue;
                  BlockModel model = (BlockModel) dispatcher.getDispatch(block);
                  if (model.renderLayer() != pass || !(model instanceof BlockModelFluid)) continue;
                  tess.setLightmapCoord2i(15, 15);
                  tess.setColor4i(255, 255, 255, 255);
                  tess.setTextureUV(0.0, 0.0);
                  tess.setShade1i(255);
                  anyRendered |= model.render(tess, cache, blockPos);
                }
              }
            } finally {
              net.betterthanadventure.btamobile.meshfix.FluidContact.mode(net.betterthanadventure.btamobile.meshfix.FluidContact.NO_CONTACT);
            }
            contactVerts = tess.data.vertexCount;
          }
          for (blockPos.y = y0; blockPos.y < y1; blockPos.y++) {
            if (cancelled) return;
            for (blockPos.z = z0; blockPos.z < z1; blockPos.z++) {
              for (blockPos.x = x0; blockPos.x < x1; blockPos.x++) {
                Block<?> block = cache.getBlockType(blockPos);
                if (!startedDrawing) {
                  startedDrawing = true;
                  tess.startDrawingQuads();
                  tess.setTranslation(translateX, 0.0, translateZ);
                }
                if (pass == 0) {
                  if (block.isEntityTile()) addTile(blockPos.x, blockPos.y, blockPos.z);
                  if (!hasWater && block.getMaterial() == net.minecraft.core.block.material.Materials.WATER) hasWater = true;
                  if (block.isSolidRender()) {
                    int cellIndex = (blockPos.x - x0) | (blockPos.z - z0) << 4 | (blockPos.y - y0) << 8;
                    opaque[cellIndex >>> 6] |= 1L << cellIndex;
                  }
                }
                BlockModel model = (BlockModel) dispatcher.getDispatch(block);
                if (model.renderLayer() != pass) {
                  needsMore = true;
                } else {
                  tess.setLightmapCoord2i(15, 15);
                  tess.setColor4i(255, 255, 255, 255);
                  tess.setTextureUV(0.0, 0.0);
                  tess.setShade1i(255);
                  anyRendered |= model.render(tess, cache, blockPos);
                }
              }
            }
          }
          if (startedDrawing) {
            ByteBuffer buffer = tess.data.getBuffer();
            int byteCount = buffer.position();
            byte[] geometry = net.betterthanadventure.btamobile.meshfix.BytePool.take(byteCount);
            buffer.get(0, geometry, 0, byteCount);
            bytes[pass] = geometry;
            length[pass] = byteCount;
            vertexCount[pass] = tess.data.vertexCount;
            started[pass] = true;
            rendered[pass] = anyRendered;
            tess.drawing = false;
            tess.setTranslation(0.0, 0.0, 0.0);
          }
          if (!needsMore) break;
        }
        if (x1 - x0 == 16 && y1 - y0 == 16 && z1 - z0 == 16) {
          try { visData = net.betterthanadventure.btamobile.meshfix.VisGraph.compute(opaque); }
          catch (Throwable ignored) { visData = net.betterthanadventure.btamobile.meshfix.VisGraph.ALL; }
        }
      } catch (Throwable error) {
        failed = true;
        Mesher.reportError(error);
      } finally {
        net.betterthanadventure.btamobile.meshfix.FluidContact.mode(net.betterthanadventure.btamobile.meshfix.FluidContact.ALL);
        tess.drawing = false;
        tess.setTranslation(0.0, 0.0, 0.0);
      }
    }
  }
}
