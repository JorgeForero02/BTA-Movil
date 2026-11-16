package net.betterthanadventure.btamobile.meshfix;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Tiny main-thread profiler for frame spikes on phones; off by default (-Dbta.prof=true, threshold
 * -Dbta.prof.ms). Patched methods call enter(id)/exit(id); a frame over the threshold prints its breakdown.
 * Every 10 s it also prints "[BTA-prof] 10s" (FPS, spikes) and "[BTA-diag]" lines: uploads by path,
 * synchronous edits and chunk saves, with their times.
 * Also holds the always-on fixes the patcher wires in (tickable, tickRadius, saveLimit, vboSize).
 */
public final class Profiler {
  public static final boolean ON = "true".equalsIgnoreCase(System.getProperty("bta.prof"));
  private static final boolean TICKABLE_FIX = !"false".equalsIgnoreCase(System.getProperty("bta.randomTickNoLoad"));
  /** Fixed chunks saved per tick (default 2, the tested setting); with -Dbta.saveBudgetMs=N a time budget instead. */
  private static final boolean SAVE_BY_TIME = Integer.getInteger("bta.saveBudgetMs") != null && Integer.getInteger("bta.savePerTick") == null;
  private static final int SAVE_PER_TICK = Math.max(1, Math.min(5, Integer.getInteger("bta.savePerTick", 2)));
  private static final long SAVE_BUDGET_NS = Math.max(1, Math.min(50, Integer.getInteger("bta.saveBudgetMs", 8))) * 1_000_000L;
  private static final boolean TICK_RADIUS_FIX = !"false".equalsIgnoreCase(System.getProperty("bta.tickRadiusFix"));
  private static final long THRESHOLD_NS = Integer.getInteger("bta.prof.ms", 25) * 1_000_000L;

  public static final int FRAME = 0, TICK = 1, WORLD_TICK = 2, LIGHT = 3, ENTITIES = 4, CHUNK_PROVIDER_TICK = 5, DECORATE = 6,
      LOAD_CHUNK = 7, SAVE_CHUNK = 8, ADD_CHUNK = 9, DIRTY = 10, EXPAND_VBO = 11, BLOCKS = 12, PENDING = 13,
      RENDER_GLOBAL_RENDER = 14, REPOSITION = 15, DRAIN = 16, SNAPSHOT = 17, TERRAIN_SOLID = 18, TERRAIN_TRANS = 19,
      TEXTURES = 20, RANDOM_DISPLAY = 21, PARTICLES = 22, RENDER_GLOBAL_TICK = 23, UPDATE_RENDERER = 24, MOUSE_OVER = 25, CAVE_CULL = 26, PROBE_COUNT = 27;
  static final String[] NAMES = {"frame", "tick", "worldTick", "light", "entities", "chunkProvTick", "decorate",
      "loadChunkSync", "saveChunk", "addChunk", "dirtyChunks", "expandVBO", "randomTicks", "pendingTicks",
      "rgRender", "reposition", "meshUpload", "meshSnapshot", "drawSolid", "drawTransl",
      "textures", "randomDisplay", "particles", "rgTick", "updRenderer", "mouseOver", "caveCull"};
  static {
    if (!ON && System.getProperty("bta.prof.sample") != null) // ProfilerSampler is never loaded with the profiler off
      System.out.println("[BTA-sample] -Dbta.prof.sample needs -Dbta.prof=true: sampling off");
  }

  private static final long[] start = new long[PROBE_COUNT];
  private static final long[] elapsedNs = new long[PROBE_COUNT];
  private static final int[] calls = new int[PROBE_COUNT];
  private static Thread main;
  private static long frameStart, lastPrint, lastSummary, gcLast;
  private static int frames, spikes;
  private static long worstFrame;
  private static List<GarbageCollectorMXBean> gcs; // created lazily, only when profiling is on
  public static int submitted, uploaded; // mesher counters (main thread)

  // 10 s diagnostic counters (main thread)
  private static final long[] uploadCount = new long[3], uploadNs = new long[3], uploadMaxNs = new long[3], uploadBytes = new long[3];
  private static long syncCount, syncNs, syncMaxNs, saveCount, saveNs, saveMaxNs, saveTicks;
  private static int saveMaxPerTick;
  private static long frameUpNs, frameSyncNs, frameSaveNs; // this frame, for the spike line

  /** One upload took durationNs by path (0 mapped, 1 fenced -> glBufferSubData, 2 glBufferSubData). */
  public static void upload(int path, long durationNs, int bytes) {
    uploadCount[path]++; uploadNs[path] += durationNs; uploadBytes[path] += bytes;
    if (durationNs > uploadMaxNs[path]) uploadMaxNs[path] = durationNs;
    frameUpNs += durationNs;
  }

  /** One player-changed section meshed and uploaded on the main thread (AsyncChunkRenderer.rebuildNow). */
  public static void syncEdit(long durationNs) {
    if (!ON) return;
    syncCount++; syncNs += durationNs; if (durationNs > syncMaxNs) syncMaxNs = durationNs;
    frameSyncNs += durationNs;
  }

  private static String ms(long nanos) { return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1e6); }

  private static long frameId;
  /** Rendered frames so far (Profiler.enter(FRAME) is always patched in, profiling on or off). */
  public static long frameId() { return frameId; }

  public static void enter(int id) {
    if (id == FRAME) {
      frameId++;
      net.betterthanadventure.btamobile.glesfix.GlErrors.frame();
      FluidContact.frameStart();
      if (ON) frame();
      if (MemReport.ON) MemReport.tick();
      return;
    }
    if (!ON) return;
    if (Thread.currentThread() != main) return;
    start[id] = System.nanoTime();
    if (ProfilerSampler.ON && id == ProfilerSampler.TARGET) ProfilerSampler.enter();
  }

  public static void exit(int id) {
    if (!ON || id == FRAME || Thread.currentThread() != main) return;
    if (ProfilerSampler.ON && id == ProfilerSampler.TARGET) ProfilerSampler.exit();
    long began = start[id];
    if (began != 0) { elapsedNs[id] += System.nanoTime() - began; calls[id]++; start[id] = 0; }
  }

  private static long gcTime() {
    long total = 0;
    if (gcs == null) {
      try { gcs = ManagementFactory.getGarbageCollectorMXBeans(); } catch (Throwable e) { gcs = java.util.Collections.emptyList(); }
    }
    for (GarbageCollectorMXBean bean : gcs) { long time = bean.getCollectionTime(); if (time > 0) total += time; }
    return total;
  }

  private static void frame() {
    long now = System.nanoTime();
    if (main == null) { main = Thread.currentThread(); frameStart = now; lastSummary = now; gcLast = gcTime(); return; }
    if (Thread.currentThread() != main) return;
    long frameNs = now - frameStart;
    frames++;
    if (frameNs > worstFrame) worstFrame = frameNs;
    long gcNow = gcTime();
    long gcDelta = gcNow - gcLast;
    gcLast = gcNow;
    if (frameNs > THRESHOLD_NS) {
      spikes++;
      if (now - lastPrint > 250_000_000L) {
        lastPrint = now;
        StringBuilder line = new StringBuilder("[BTA-prof] frame ").append(frameNs / 1_000_000).append("ms:");
        for (int i = 1; i < PROBE_COUNT; i++) {
          long probeMs = elapsedNs[i] / 1_000_000;
          if (probeMs >= 2) line.append(' ').append(NAMES[i]).append('=').append(probeMs).append(calls[i] > 1 ? "x" + calls[i] : "");
        }
        if (gcDelta > 0) line.append(" GC=").append(gcDelta);
        if (frameUpNs >= 1_000_000L) line.append(" uploadGL=").append(ms(frameUpNs));
        if (frameSyncNs >= 1_000_000L) line.append(" syncEdits=").append(ms(frameSyncNs));
        if (frameSaveNs >= 1_000_000L) line.append(" saves=").append(ms(frameSaveNs));
        line.append(" | mesh sub=").append(submitted).append(" up=").append(uploaded).append(" q=").append(Mesher.inFlight());
        System.out.println(line);
      }
    }
    if (now - lastSummary > 10_000_000_000L) {
      double secs = (now - lastSummary) / 1e9;
      System.out.println("[BTA-prof] 10s: avg " + Math.round(frames / secs) + " fps, spikes>" + THRESHOLD_NS / 1_000_000 + "ms: " + spikes + ", worst " + worstFrame / 1_000_000 + "ms");
      String[] pathNames = {"mapped", "fenced", "subData"};
      StringBuilder uploads = new StringBuilder("[BTA-diag] uploads:");
      for (int p = 0; p < 3; p++)
        uploads.append(' ').append(pathNames[p]).append('=').append(uploadCount[p]).append(" (").append(ms(uploadNs[p])).append("ms total, max ")
            .append(ms(uploadMaxNs[p])).append("ms, ").append(uploadBytes[p] >> 20).append("MB)");
      uploads.append(" pendingRanges=").append(net.betterthanadventure.btamobile.zalithfix.BufferUpload.pendingRanges());
      System.out.println(uploads);
      System.out.println("[BTA-diag] syncEdits=" + syncCount + " (" + ms(syncNs) + "ms total, max " + ms(syncMaxNs) + "ms)"
          + " | saves=" + saveCount + " in " + saveTicks + " ticks (max " + saveMaxPerTick + "/tick, " + ms(saveNs) + "ms total, max "
          + ms(saveMaxNs) + "ms each, avg estimate " + ms(saveCostNs) + "ms)");
      java.util.Arrays.fill(uploadCount, 0); java.util.Arrays.fill(uploadNs, 0); java.util.Arrays.fill(uploadMaxNs, 0); java.util.Arrays.fill(uploadBytes, 0);
      syncCount = syncNs = syncMaxNs = saveCount = saveNs = saveMaxNs = saveTicks = 0; saveMaxPerTick = 0;
      lastSummary = now; frames = 0; spikes = 0; worstFrame = 0;
    }
    for (int i = 0; i < PROBE_COUNT; i++) { elapsedNs[i] = 0; calls[i] = 0; }
    submitted = 0; uploaded = 0;
    frameUpNs = frameSyncNs = frameSaveNs = 0;
    frameStart = now;
  }

  /**
   * Replaces World.isChunkLoaded(cx, cz) in the random-tick loop: a chunk is only random-ticked when its
   * 8 neighbours are loaded too. Otherwise a block at the chunk border made BTA load or generate the
   * neighbour on the main thread (12-50 ms each). -Dbta.randomTickNoLoad=false restores the original check.
   */
  public static boolean tickable(net.minecraft.core.world.World world, int cx, int cz) {
    if (TICKABLE_FIX) {
      for (int dx = -1; dx <= 1; dx++)
        for (int dz = -1; dz <= 1; dz++)
          if ((dx != 0 || dz != 0) && !world.isChunkLoaded(cx + dx, cz + dz)) return false;
    }
    return world.isChunkLoaded(cx, cz); // centre last, so BTA's last-queried-chunk cache holds it for the next lookup
  }

  private static int renderDistance() {
    try { return (Integer) net.minecraft.client.option.GameSettings.RENDER_DISTANCE.value; } catch (Throwable t) { return 12; }
  }

  /** Chunks saved per tick when unloading: 2 (BTA: 5, ~4 ms each on phones), BTA's 5 when far more
   *  chunks are loaded than the render distance needs (after a teleport/respawn).
   *  The optional time budget (-Dbta.saveBudgetMs=N: as many as fit, 1..5) is off by default: on a phone it saved 5 per tick
   *  most of the time (~1.5 ms each on the main thread, some 20-35 ms) with no gain.
   *  Called by the patched save loop after each save, with the number saved so far this tick (saved >= 1);
   *  the loop goes on while saved <= the returned value. */
  public static int saveLimit(int saved, java.util.Map<?, ?> loaded) {
    long now = clock.getAsLong();
    if (saved <= 1) saveLoopStart = saveStart;
    long costNs = now - saveStart; // this save: saveChunk + onUnload + remove from the map
    if (costNs > 0) saveCostNs += (Math.min(costNs, 50_000_000L) - saveCostNs) / 4; // moving average, outliers capped
    if (ON && costNs > 0) {
      saveCount++; saveNs += costNs; frameSaveNs += costNs; if (costNs > saveMaxNs) saveMaxNs = costNs;
      if (saved <= 1) saveTicks++;
      if (saved > saveMaxPerTick) saveMaxPerTick = saved;
    }
    try {
      int radius = renderDistance() + 1, expected = (2 * radius + 1) * (2 * radius + 1);
      if (loaded != null && loaded.size() > expected + 64) return 4; // BTA's 5 per tick
    } catch (Throwable ignored) { }
    if (!SAVE_BY_TIME) return SAVE_PER_TICK - 1;
    if (saved >= 5) return saved - 1;                                           // never more than BTA's 5
    return now - saveLoopStart + saveCostNs <= SAVE_BUDGET_NS ? saved : saved - 1; // room for one more?
  }

  /** Right before each saveChunk in the save loop (patched in). */
  public static void saveBegin() { saveStart = clock.getAsLong(); }

  private static long saveLoopStart, saveStart, saveCostNs = 4_000_000L;
  /** Time source of the save budget; tests replace it so the budget is measured without timing noise. */
  static java.util.function.LongSupplier clock = System::nanoTime;

  /** Random-tick radius in chunks. BTA uses 9, but chunks only stay loaded to renderDistance+1, so
   *  edge chunks kept loading (synchronously) and unloading neighbours every tick. */
  public static int tickRadius(net.minecraft.core.world.World world) {
    if (!TICK_RADIUS_FIX) return 9;
    int renderDistance;
    try { renderDistance = world.renderDistance(); } catch (Throwable t) { return 9; }
    return Math.max(2, Math.min(9, renderDistance));
  }

  /** Initial terrain VBO size. BTA starts at 128 MB and grows x1.5 while playing (each growth stalls),
   *  so start at the size the render distance usually ends up needing. -Dbta.vboMB=N overrides. */
  public static long vboSize() {
    long megabytes;
    Long forced = Long.getLong("bta.vboMB");
    if (forced != null) megabytes = Math.max(64L, forced);
    else {
      int renderDistance = renderDistance();
      megabytes = renderDistance <= 4 ? 128L : renderDistance <= 8 ? 192L : 288L;
    }
    return megabytes * 1024L * 1024L;
  }
}
