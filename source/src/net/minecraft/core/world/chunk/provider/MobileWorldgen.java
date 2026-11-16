package net.minecraft.core.world.chunk.provider;

import net.minecraft.client.Minecraft;
import net.minecraft.core.world.World;
import net.minecraft.core.world.chunk.Chunk;
import net.minecraft.core.world.pos.ChunkPos;
import net.minecraft.core.world.pos.ChunkPosc;

/**
 * Smoother world decoration (trees, ores...) without changing how it is generated.
 *
 * Budget: per tick, decorations run until they have used ~12 ms (always at least one). Cheap chunks
 * (ocean, plains) go many per tick; heavy forests one per tick. A chunk over
 * budget is put back into BTA's own "check if it can be decorated" set (a set, so no duplicates) and is
 * retried next tick, so it is never lost; BTA also saves isTerrainPopulated, so an unloaded chunk is
 * decorated when it loads again. Chunks within 4 of the player and spawn search are never throttled.
 * The renderer waits (up to 20 s) for a chunk to be decorated before showing it, so bare terrain is
 * not shown. All of this runs on the main thread, exactly where BTA does it.
 */
public final class MobileWorldgen {
  private static final int MIN_PER_TICK = Math.max(1, Integer.getInteger("bta.decoratePerTick", 1)); // minimum per tick
  private static final long BUDGET_NS = Math.max(1, Integer.getInteger("bta.decorateBudgetMs", 12)) * 1_000_000L;
  private static long spentNs, decoStart;
  private static int depth;                       // decorate() nesting (cascading decoration)
  // positions, not Chunk objects: a chunk unloaded and reloaded before the next tick is a new object,
  // and handing the old one back to BTA would decorate it twice
  static final java.util.Set<ChunkPos> requeue = new java.util.HashSet<>();
  private static java.lang.ref.WeakReference<ChunkProviderThreaded> requeueOwner = new java.lang.ref.WeakReference<>(null); // weak: never keeps an old world alive
  private static final int NEAR = 4;
  /** Smooth decoration also defers nested (cascading) decorations; -Dbta.deferCascade=false turns only this off. */
  private static final boolean DEFER_CASCADE = !"false".equalsIgnoreCase(System.getProperty("bta.deferCascade"));
  private static int lastX, lastZ;                // last position added to requeue, to skip repeats
  private static boolean lastValid;
  /** Test-only (-Dbta.test.decoCounters=true): off in the game. */
  static final boolean COUNTERS = Boolean.getBoolean("bta.test.decoCounters");
  /** Test-only: positions deferred to the next tick, append-only. Null unless COUNTERS. */
  static final java.util.List<ChunkPos> deferred = COUNTERS ? new java.util.ArrayList<>() : null;
  private static int decoratedThisTick;
  private static long lastActivity; // last decoration check / decoration end / tick start
  /** After this long without any decoration activity the budget starts again: decoration reached
   *  outside the provider's tick (or with the tick not running for a while) is not left throttled by
   *  an old budget. Measured from the last activity, so a long decoration never resets it. */
  private static final long IDLE_RESET_NS = 100_000_000L;

  public static boolean throttleEnabled() {
    return !"false".equalsIgnoreCase(System.getProperty("bta.smoothDecoration"))
        && net.betterthanadventure.btamobile.meshfix.MobileOptions.smoothDecoration();
  }

  // ------------------------------------------------------------------ decoration budget

  /** Replaces isChunkValid(pos) inside ChunkProviderThreaded.handleDecoration. */
  public static boolean validForDecoration(ChunkProviderThreaded provider, ChunkPosc pos) {
    if (!provider.isChunkValid(pos)) return false;
    try {
      if (!throttleEnabled() || provider.world.findingSpawnPoint) return true;
      if (depth > 0 && DEFER_CASCADE && !nearPlayer(provider, pos)) { // nested decoration: next tick
        requeue(provider, pos);
        if (COUNTERS) deferred.add(new ChunkPos(pos.x(), pos.z()));
        return false;
      }
      long now = System.nanoTime();
      if (depth == 0 && now - lastActivity > IDLE_RESET_NS) { decoratedThisTick = 0; spentNs = 0; }
      lastActivity = now;
      if (decoratedThisTick < MIN_PER_TICK) return true;
      long spent = spentNs + (depth > 0 ? System.nanoTime() - decoStart : 0);
      if (spent < BUDGET_NS) return true;
      if (nearPlayer(provider, pos)) return true;
      Chunk chunk;
      synchronized (provider.loadedChunks) { chunk = provider.loadedChunks.get(pos); }
      if (chunk == null) return true; // not something we can queue: let BTA handle it as usual
      requeue(provider, pos);
      return false;
    } catch (Throwable ignored) {
      return true; // never block decoration because of our own code
    }
  }

  private static boolean nearPlayer(ChunkProviderThreaded provider, ChunkPosc pos) {
    ChunkPos playerChunk = provider.chunkOver;
    return playerChunk != null && Math.abs(playerChunk.x - pos.x()) <= NEAR && Math.abs(playerChunk.z - pos.z()) <= NEAR;
  }

  private static void requeue(ChunkProviderThreaded provider, ChunkPosc pos) {
    if (requeueOwner.get() != provider) { clearQueue(); requeueOwner = new java.lang.ref.WeakReference<>(provider); }
    if (lastValid && lastX == pos.x() && lastZ == pos.z()) return; // same position again: already queued
    lastX = pos.x(); lastZ = pos.z(); lastValid = true;
    requeue.add(new ChunkPos(pos.x(), pos.z())); // handed back to BTA's own queue at the start of the next tick (no nested locks)
  }

  private static void clearQueue() {
    requeue.clear();
    lastValid = false;
  }

  /** Called at the start of ChunkProviderThreaded.decorate(chunk). */
  public static void onDecorate(Chunk chunk) {
    if (depth++ == 0) {
      decoStart = System.nanoTime();
      if (chunk != null && !chunk.isTerrainPopulated) decoratedThisTick++;
    }
  }

  /** Called when ChunkProviderThreaded.decorate returns, normally or by exception (try/finally wrapper). */
  public static void onDecorateEnd() {
    if (depth > 0 && --depth == 0) { long now = System.nanoTime(); spentNs += now - decoStart; lastActivity = now; }
  }

  /** Called at the start of ChunkProviderThreaded.tick(): new decoration budget. */
  public static void onTick(ChunkProviderThreaded provider) {
    decoratedThisTick = 0;
    spentNs = 0;
    lastActivity = System.nanoTime();
    if (requeueOwner.get() != provider) { clearQueue(); requeueOwner = new java.lang.ref.WeakReference<>(provider); return; }
    if (requeue.isEmpty()) return;
    java.util.List<Chunk> retryChunks = new java.util.ArrayList<>(requeue.size());
    synchronized (provider.loadedChunks) {
      for (ChunkPos pos : requeue) {
        Chunk chunk = provider.loadedChunks.get(pos); // the chunk loaded NOW at that position (or none)
        if (chunk != null && !chunk.isTerrainPopulated) retryChunks.add(chunk);
      }
    }
    clearQueue();
    synchronized (provider.positionsToCheckIfCanDecorate) {
      for (Chunk chunk : retryChunks) provider.positionsToCheckIfCanDecorate.add(chunk);
    }
  }

  // ------------------------------------------------------------------ render: hide undecorated chunks

  /** True if the chunk column is loaded and not decorated yet (renderer waits instead of showing bare terrain). */
  public static boolean waitingForDecoration(World world, int chunkX, int chunkZ) {
    if (!net.betterthanadventure.btamobile.meshfix.MobileOptions.hideUndecorated() || world == null || world.isClientSide) return false;
    try {
      Minecraft mc = Minecraft.getMinecraft();
      if (mc.thePlayer != null) {
        int playerChunkX = (int) Math.floor(mc.thePlayer.x) >> 4, playerChunkZ = (int) Math.floor(mc.thePlayer.z) >> 4;
        if (Math.abs(playerChunkX - chunkX) <= NEAR && Math.abs(playerChunkZ - chunkZ) <= NEAR) return false; // never hide around the player
      }
      ChunkProvider chunkProvider = world.getChunkProvider();
      if (!(chunkProvider instanceof ChunkProviderThreaded)) return false;
      ChunkProviderThreaded provider = (ChunkProviderThreaded) chunkProvider;
      // BTA decorates chunk C into the area offset by +8 blocks, so the blocks of column (chunkX,chunkZ) come from
      // the decoration of (chunkX,chunkZ), (chunkX-1,chunkZ), (chunkX,chunkZ-1) and (chunkX-1,chunkZ-1). Wait for all of them (if loaded).
      synchronized (provider.loadedChunks) {
        for (int dx = -1; dx <= 0; dx++)
          for (int dz = -1; dz <= 0; dz++) {
            Chunk chunk = provider.loadedChunks.get(new ChunkPos(chunkX + dx, chunkZ + dz));
            if (chunk != null && !chunk.isTerrainPopulated) return true;
          }
      }
      return false;
    } catch (Throwable ignored) {
      return false;
    }
  }
}
