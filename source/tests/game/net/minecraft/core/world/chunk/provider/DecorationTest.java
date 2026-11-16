package net.minecraft.core.world.chunk.provider;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.block.Block;
import net.minecraft.core.block.Blocks;
import net.minecraft.core.block.entity.TileEntity;
import net.minecraft.core.world.Dimension;
import net.minecraft.core.world.World;
import net.minecraft.core.world.biome.provider.BiomeProviderOverworld;
import net.minecraft.core.world.chunk.Chunk;
import net.minecraft.core.world.chunk.ChunkLoader;
import net.minecraft.core.world.generate.chunk.ChunkGenerator;
import net.minecraft.core.world.pos.ChunkPos;
import net.minecraft.core.world.pos.ChunkPosc;
import net.minecraft.core.world.pos.TilePos;
import net.minecraft.core.world.save.LevelData;
import net.minecraft.core.world.save.LevelStorage;
import net.minecraft.core.world.save.LevelStorageDummy;
import net.minecraft.core.world.settings.WorldConfiguration;

/**
 * Generates a patch of world headless with the real game (BTA plus the patches) and checks that
 * plain BTA is deterministic. Each case runs in its own JVM (properties -Dtest.case and
 * -Dtest.dir), only generates and dumps to a file; the last JVM (-Dtest.case=compare) applies
 * every criterion, collects the failures and exits with 1.
 *
 * Cases: A and Ap (spiral, plain BTA, twice), B (rows from west to east, plain BTA), M (5x5
 * assembly around the center, only to see the real decoration order), T (smooth decoration is
 * active headless and the deferred list is null without counters) and, with smooth decoration
 * and the counters active, C (cascade not deferred), D (deferred), E (with a player at the
 * center), F (deferred position with its chunk unloaded and loaded again) and G (the
 * MobileWorldgen queue decorates what BTA does not have in its set).
 */
public class DecorationTest {
  // 15x15 chunk zone far from the spawn point; static constants because the World constructor
  // calls createChunkProvider before the subclass fields exist
  static final int ZX0 = 1000, ZZ0 = 1000, N = 15;
  static final long SEED = 20261101L;
  static final int CORE0 = 2, CORE1 = 12, CORE_SIZE = CORE1 - CORE0 + 1;
  static final int MAX_TICKS = 3000;

  // ---- state of the case JVM (static: written by the provider, which is created inside the World constructor)
  static int depth, maxDepth;
  static final int[][] decorations = new int[N][N];     // real decorations per position
  static final int[][] nested = new int[N][N];      // real decorations with depth > 1
  static final List<String> order = new ArrayList<>();
  static int[] pendingSnapshot;                     // x, z, loaded-neighbor mask at the last zone isChunkValid (null once used)
  static int validCalls;
  static final List<int[]> snapshots = new ArrayList<>();  // x, z, mask: one per MobileWorldgen.deferred entry
  static final StringBuilder snapshotError = new StringBuilder();
  static final Map<String, String> observations = new java.util.LinkedHashMap<>(); // observations at the moment (F and G)

  static boolean inZone(int cx, int cz) {
    return cx >= ZX0 && cx < ZX0 + N && cz >= ZZ0 && cz < ZZ0 + N;
  }

  /** Mask of the 8 neighbors of pos that are in loadedChunks right now. */
  static int neighborMask(Map<ChunkPos, Chunk> loaded, int cx, int cz) {
    int m = 0, b = 0;
    for (int dx = -1; dx <= 1; dx++)
      for (int dz = -1; dz <= 1; dz++) {
        if (dx == 0 && dz == 0) continue;
        if (loaded.containsKey(new ChunkPos(cx + dx, cz + dz))) m |= 1 << b;
        b++;
      }
    return m;
  }

  /** Assigns the pending snapshot to the entry that the branch added to the deferred list since the last time.
   *  Each deferral follows exactly one isChunkValid call; if the snapshot position is not the entry's,
   *  it is noted ("unpaired snapshot") and compare fails. */
  static void syncSnapshot() {
    List<ChunkPos> d = MobileWorldgen.deferred;
    if (d == null) return;
    int added = d.size() - snapshots.size();
    if (added > 1) snapshotError.append("unpaired snapshot: ").append(added).append(" deferred without an isChunkValid call between them; ");
    for (int i = snapshots.size(); i < d.size(); i++) {
      ChunkPos pos = d.get(i);
      int[] f = pendingSnapshot;
      if (f == null || f[0] != pos.x || f[1] != pos.z || added > 1) {
        snapshotError.append("unpaired snapshot: entry ").append(i).append(" at ").append(pos.x).append(',').append(pos.z)
            .append(" with snapshot ").append(f == null ? "none" : f[0] + "," + f[1]).append("; ");
        snapshots.add(new int[]{pos.x, pos.z, -1});
      } else {
        snapshots.add(f);
      }
      pendingSnapshot = null;
    }
  }

  static void resetCounters() {
    depth = 0; maxDepth = 0; validCalls = 0;
    pendingSnapshot = null; snapshots.clear(); snapshotError.setLength(0); observations.clear();
    for (int[] f : decorations) Arrays.fill(f, 0);
    for (int[] f : nested) Arrays.fill(f, 0);
    order.clear();
  }

  /** Game provider with the zone as the only valid region and a decoration counter. */
  static final class TestProvider extends ChunkProviderThreaded {
    TestProvider(World w, ChunkLoader l, ChunkGenerator g) { super(w, l, g); }

    @Override protected boolean isChunkValid(ChunkPosc pos) {
      boolean z = inZone(pos.x(), pos.z());
      syncSnapshot();
      if (z) {
        validCalls++;
        pendingSnapshot = new int[]{pos.x(), pos.z(), neighborMask(loadedChunks, pos.x(), pos.z())};
      }
      return z;
    }

    /** BTA only calls decorate from populate and handleDecoration. isTerrainPopulated is read before
     *  super (decorate$bta sets it to true): only those entries are real decoration. */
    @Override public void decorate(Chunk c) {
      boolean real = !c.isTerrainPopulated;
      depth++;
      try {
        if (real) {
          if (depth > maxDepth) maxDepth = depth;
          int ix = c.pos.x - ZX0, iz = c.pos.z - ZZ0;
          if (ix >= 0 && ix < N && iz >= 0 && iz < N) {
            decorations[ix][iz]++;
            if (depth > 1) nested[ix][iz]++;
            order.add(ix + "," + iz + "," + depth);
          }
        }
        super.decorate(c);
      } finally {
        depth--;
      }
    }
  }

  /** In-memory loader: nothing is saved, everything is regenerated. */
  static final class InMemoryLoader implements ChunkLoader {
    @Override public Chunk loadChunk(World w, int x, int z) { return null; }
    @Override public void saveChunk(World w, Chunk c) { }
    @Override public boolean chunkExists(World w, int x, int z) { return false; }
    @Override public boolean isSaving() { return false; }
  }

  /** Diskless storage that returns ready-made level data: with a null getLevelData() World thinks the
   *  world is new and searches for a spawn point generating chunks forever. */
  static final class TestStorage extends LevelStorageDummy {
    private final LevelData data;
    TestStorage(WorldConfiguration c) { data = new LevelData(c); }
    @Override public LevelData getLevelData() { return data; }
  }

  static final class TestWorld extends World {
    TestWorld(WorldConfiguration c) { super(new TestStorage(c), c, Dimension.OVERWORLD); }
    @Override protected net.minecraft.core.world.chunk.provider.ChunkProvider createChunkProvider(LevelStorage s) {
      return new TestProvider(this, new InMemoryLoader(), getWorldType().createChunkGenerator(this));
    }
    @Override public void setLightningFlicker(int i) { }
  }

  // ------------------------------------------------------------------ assembly

  static TestProvider provider;
  static TestWorld world;

  static int[][] spiral() {
    List<int[]> l = new ArrayList<>();
    int x = N / 2, z = N / 2, d = 0, length = 1;
    int[] dx = {1, 0, -1, 0}, dz = {0, 1, 0, -1};
    l.add(new int[]{x, z});
    while (l.size() < N * N) {
      for (int rep = 0; rep < 2; rep++) {
        for (int k = 0; k < length; k++) {
          x += dx[d]; z += dz[d];
          if (x >= 0 && x < N && z >= 0 && z < N) l.add(new int[]{x, z});
        }
        d = (d + 1) % 4;
      }
      length++;
    }
    return l.toArray(new int[0][]);
  }

  static int[][] rows() {
    int[][] r = new int[N * N][];
    int i = 0;
    for (int iz = 0; iz < N; iz++) for (int ix = 0; ix < N; ix++) r[i++] = new int[]{ix, iz};
    return r;
  }

  static void load(int ix, int iz) {
    provider.provideChunk(new ChunkPos(ZX0 + ix, ZZ0 + iz), true);
  }

  static Chunk read(int ix, int iz) {
    synchronized (provider.loadedChunks) { return provider.loadedChunks.get(new ChunkPos(ZX0 + ix, ZZ0 + iz)); }
  }

  /** Decoration state of the zone read without side effects (loadedChunks objects, never World or provideChunk). */
  static String signature() {
    StringBuilder s = new StringBuilder();
    for (int iz = 0; iz < N; iz++) for (int ix = 0; ix < N; ix++) {
      Chunk c = read(ix, iz);
      s.append(c == null ? '-' : c.isTerrainPopulated ? 'P' : 'u');
    }
    return s.toString();
  }

  static boolean decoratablesReady() {
    for (int iz = 1; iz < N - 1; iz++) for (int ix = 1; ix < N - 1; ix++) {
      Chunk c = read(ix, iz);
      if (c == null || !c.isTerrainPopulated) return false;
    }
    return true;
  }

  static boolean queuesEmpty() {
    synchronized (provider.positionsToCheckIfCanDecorate) {
      if (!provider.positionsToCheckIfCanDecorate.isEmpty()) return false;
    }
    synchronized (provider.positionsToCheckIfCanDecorateCopy) {
      return provider.positionsToCheckIfCanDecorateCopy.isEmpty();
    }
  }

  /** Cheap hash of the blocks (id and metadata) of every loaded chunk of the zone, read from loadedChunks. */
  static long blocksHash() {
    int h = world.getHeightBlocks();
    long r = 1125899906842597L;
    for (int iz = 0; iz < N; iz++) for (int ix = 0; ix < N; ix++) {
      Chunk c = read(ix, iz);
      if (c == null) { r = r * 31 + 7; continue; }
      for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) for (int y = 0; y < h; y++)
        r = r * 31 + c.getBlockID(lx, y, lz) * 16 + c.getBlockMetadata(lx, y, lz);
    }
    return r;
  }

  /** tick() until the decoratables are decorated, the queues empty and one more tick does not change the zone. */
  static int stabilize() {
    for (int t = 1; t <= MAX_TICKS; t++) {
      boolean ready = decoratablesReady() && queuesEmpty();
      String before = signature();
      long blocksBefore = ready ? blocksHash() : 0;
      provider.tick();
      if (ready && before.equals(signature()) && queuesEmpty() && blocksBefore == blocksHash()) return t;
    }
    throw new AssertionError("the zone does not stabilize in " + MAX_TICKS + " ticks (decoratables ready=" + decoratablesReady()
        + ", queues empty=" + queuesEmpty() + ", pending=" + provider.positionsToCheckIfCanDecorate.size() + "/"
        + provider.positionsToCheckIfCanDecorateCopy.size() + ", zone=" + signature() + ")");
  }

  // ------------------------------------------------------------------ case

  static void runCase(String testCase, File dir) throws Exception {
    String smooth = System.getProperty("bta.smoothDecoration");
    if (testCase.equals("T")) {
      boolean active = MobileWorldgen.throttleEnabled();
      boolean nullList = MobileWorldgen.deferred == null;
      write(dir, testCase, List.of("case=T", "smoothProp=" + smooth, "throttle=" + active, "countersProp=" + MobileWorldgen.COUNTERS,
          "deferredNull=" + nullList));
      System.out.println("case T: throttleEnabled=" + active + " deferredNull=" + nullList);
      return;
    }
    boolean withoutSmooth = Set.of("A", "Ap", "B", "M").contains(testCase);
    if (withoutSmooth) {
      if (!"false".equals(smooth)) throw new AssertionError("case " + testCase + " requires -Dbta.smoothDecoration=false");
      if (MobileWorldgen.throttleEnabled()) throw new AssertionError("throttleEnabled() should be false with -Dbta.smoothDecoration=false");
    } else {
      if ("false".equals(smooth)) throw new AssertionError("case " + testCase + " requires smooth decoration active");
      if (!MobileWorldgen.throttleEnabled()) throw new AssertionError("throttleEnabled() should be true in case " + testCase);
      if (!MobileWorldgen.COUNTERS || MobileWorldgen.deferred == null) throw new AssertionError("case " + testCase + " requires -Dbta.test.decoCounters=true");
      if (Integer.getInteger("bta.decorateBudgetMs", 0) < 100000) throw new AssertionError("case " + testCase + " requires a very high -Dbta.decorateBudgetMs");
      String defer = System.getProperty("bta.deferCascade");
      if (testCase.equals("C") != "false".equals(defer)) throw new AssertionError("-Dbta.deferCascade=false belongs to case C only (case " + testCase + ", value " + defer + ")");
    }

    long t0 = System.nanoTime();
    Blocks.init();
    Dimension.init();
    BiomeProviderOverworld.init(); // fills and locks the Overworld biome map (the game startup does it)
    WorldConfiguration cfg = new WorldConfiguration();
    cfg.setNumericSeed(SEED);
    cfg.setWorldName("decoration-test");
    world = new TestWorld(cfg);
    if (!(world.getChunkProvider() instanceof TestProvider)) throw new AssertionError("the world does not use the test provider");
    provider = (TestProvider) world.getChunkProvider();
    resetCounters();
    long tWorld = System.nanoTime();

    int[][] route = testCase.equals("B") ? rows() : spiral();
    String setupOrder = "";
    if (testCase.equals("E")) provider.setCurrentChunkOver(new ChunkPos(ZX0 + N / 2, ZZ0 + N / 2));
    if (Set.of("M", "F", "G").contains(testCase)) {
      // assembly shared by F and G: the 5x5 around the center, the center last
      int c0 = N / 2;
      for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) if (dx != 0 || dz != 0) load(c0 + dx, c0 + dz);
      load(c0, c0);
      setupOrder = String.join(";", order);
    }
    if (testCase.equals("F") || testCase.equals("G")) stepsFG(testCase.equals("F"));
    for (int[] p : route) load(p[0], p[1]);
    int ticks = stabilize();
    long tGen = System.nanoTime();

    // counters over the decoratables, before draining the scheduled updates
    int[][] decosBefore = new int[N][N];
    for (int i = 0; i < N; i++) decosBefore[i] = decorations[i].clone();
    StringBuilder pop = new StringBuilder();
    for (int iz = 0; iz < N; iz++) for (int ix = 0; ix < N; ix++) {
      Chunk c = read(ix, iz);
      pop.append(c != null && c.isTerrainPopulated ? '1' : '0');
    }
    int n = 0;
    while (world.tickPendingTicks(true)) if (++n > MAX_TICKS) throw new AssertionError("tickPendingTicks does not finish");
    long tPending = System.nanoTime();
    if (testCase.equals("G")) checkRequeueShortcut();

    String hash = dumpBlocks(new File(dir, testCase + ".bin.gz"));
    long tDump = System.nanoTime();

    List<String> l = new ArrayList<>();
    l.add("case=" + testCase);
    l.add("smoothProp=" + smooth);
    l.add("throttle=" + MobileWorldgen.throttleEnabled());
    l.add("countersProp=" + MobileWorldgen.COUNTERS);
    l.add("deferCascadeProp=" + System.getProperty("bta.deferCascade"));
    l.add("hash=" + hash);
    l.add("height=" + world.getHeightBlocks());
    l.add("ticks=" + ticks);
    l.add("pending=" + n);
    l.add("maxDepth=" + maxDepth);
    l.add("isChunkValidCalls=" + validCalls);
    syncSnapshot();
    List<ChunkPos> deferredList = MobileWorldgen.deferred == null ? List.of() : MobileWorldgen.deferred;
    StringBuilder listing = new StringBuilder();
    int withEight = 0;
    for (int i = 0; i < deferredList.size(); i++) {
      int[] f = snapshots.get(i);
      if (listing.length() > 0) listing.append(';');
      listing.append(f[0] - ZX0).append(',').append(f[1] - ZZ0).append(',').append(f[2]);
      if (f[2] == 255) withEight++;
    }
    l.add("deferred=" + deferredList.size());
    l.add("deferredWithEight=" + withEight);
    l.add("deferredList=" + listing);
    l.add("snapshotError=" + snapshotError);
    for (Map.Entry<String, String> o : observations.entrySet()) l.add("obs" + o.getKey() + "=" + o.getValue());
    l.add("decorations=" + flatByRows(decosBefore));
    l.add("nested=" + flatByRows(nested));
    l.add("pop=" + pop);
    l.add("order=" + String.join(";", order));
    l.add("setupOrder=" + setupOrder);
    l.add("zoneEntities=" + zoneEntities());
    l.add("noCollision=" + noCollision);
    l.add("collisionErrors=" + collisionErrors);
    l.add("collisionChangedZone=" + collisionChangedZone);
    l.add("msWorld=" + (tWorld - t0) / 1_000_000);
    l.add("msGeneration=" + (tGen - tWorld) / 1_000_000);
    l.add("msPending=" + (tPending - tGen) / 1_000_000);
    l.add("msDump=" + (tDump - tPending) / 1_000_000);
    write(dir, testCase, l);
    System.out.println("case " + testCase + ": hash=" + hash + " ticks=" + ticks + " maxDepth=" + maxDepth
        + " ms: world=" + (tWorld - t0) / 1_000_000 + " generation=" + (tGen - tWorld) / 1_000_000
        + " pending=" + (tPending - tGen) / 1_000_000 + " dump=" + (tDump - tPending) / 1_000_000);
  }

  /** First deferred position with index 1-13, with 8 neighbors loaded according to its snapshot and not yet decorated,
   *  or null if there is none (insufficient assembly). */
  static int[] chooseN() {
    syncSnapshot();
    List<ChunkPos> d = MobileWorldgen.deferred;
    for (int i = 0; i < d.size(); i++) {
      int ix = d.get(i).x - ZX0, iz = d.get(i).z - ZZ0;
      if (ix < 1 || ix > N - 2 || iz < 1 || iz > N - 2 || snapshots.get(i)[2] != 255) continue;
      Chunk c = read(ix, iz);
      if (c != null && !c.isTerrainPopulated) return new int[]{ix, iz};
    }
    return null;
  }

  static void removeFromQueues(Chunk c) {
    synchronized (provider.positionsToCheckIfCanDecorate) { provider.positionsToCheckIfCanDecorate.remove(c); }
    synchronized (provider.positionsToCheckIfCanDecorateCopy) { provider.positionsToCheckIfCanDecorateCopy.remove(c); }
  }

  /** A simulated nested call on X: validForDecoration must return false and leave X in the queue. */
  static boolean deferNested(ChunkPos x) {
    MobileWorldgen.onDecorate(null);
    try {
      boolean valid = MobileWorldgen.validForDecoration(provider, x);
      syncSnapshot();
      return !valid && MobileWorldgen.requeue.contains(x);
    } finally {
      MobileWorldgen.onDecorateEnd();
    }
  }

  /** Protects the MobileWorldgen.requeue shortcut (do not add the last position again): after emptying the queue
   *  with onTick, the same position has to enter again. At the end of case G, with everything decorated. */
  static void checkRequeueShortcut() {
    ChunkPos x = new ChunkPos(ZX0 + N / 2, ZZ0 + N / 2);
    String r = "ok";
    if (!deferNested(x)) r = "the simulated nested call did not leave the position in the MobileWorldgen queue";
    else {
      MobileWorldgen.onTick(provider);
      if (MobileWorldgen.requeue.contains(x)) r = "onTick did not empty the MobileWorldgen queue";
      else if (!deferNested(x)) r = "requeue shortcut not invalidated after onTick";
    }
    observations.put("GRequeueShortcut", r);
  }

  /** Steps of F (unload N, request it again) and G (remove N from the BTA set and one tick), with
   *  the observations of the moment. */
  static void stepsFG(boolean isF) {
    int[] n = chooseN();
    if (n == null) { observations.put("Setup", "insufficient"); return; }
    observations.put("Setup", "ok");
    observations.put("N", n[0] + "," + n[1]);
    ChunkPos pos = new ChunkPos(ZX0 + n[0], ZZ0 + n[1]);
    Chunk c = read(n[0], n[1]);
    if (isF) {
      // as tick does when unloading, and the old object is removed from the BTA set
      synchronized (provider.loadedChunks) {
        c.onUnload();
        provider.loadedChunks.remove(pos);
      }
      provider.lastQueriedChunkPos = ChunkProviderThreaded.MIN_POS;
      provider.lastQueriedChunk = provider.empty;
      removeFromQueues(c);
      load(n[0], n[1]);
      Chunk fresh = read(n[0], n[1]);
      observations.put("FDecoratedAfterProvide", String.valueOf(fresh != null && fresh != c && fresh.isTerrainPopulated));
      observations.put("FInQueue", String.valueOf(MobileWorldgen.requeue.contains(pos)));
    } else {
      observations.put("GUndecoratedBefore", String.valueOf(!c.isTerrainPopulated));
      removeFromQueues(c);
      provider.tick();
      Chunk after = read(n[0], n[1]);
      observations.put("GDecoratedAfter", String.valueOf(after == c && c.isTerrainPopulated));
    }
  }

  /** Block entities in the whole zone (the dump only stores those of the core): if it is 0 in every case, the
   *  entity comparison proves nothing. */
  static int zoneEntities() {
    int n = 0;
    for (int iz = 0; iz < N; iz++) for (int ix = 0; ix < N; ix++) { Chunk c = read(ix, iz); if (c != null) n += c.tileEntityMap.size(); }
    return n;
  }

  static String flatByRows(int[][] a) {
    StringBuilder s = new StringBuilder();
    for (int iz = 0; iz < N; iz++) for (int ix = 0; ix < N; ix++) { if (s.length() > 0) s.append(','); s.append(a[ix][iz]); }
    return s.toString();
  }

  static void write(File dir, String testCase, List<String> lines) throws Exception {
    Files.write(new File(dir, testCase + ".txt").toPath(), lines, StandardCharsets.UTF_8);
  }

  static String noCollision = "";
  static int collisionErrors;
  static boolean collisionChangedZone;

  /** Id and metadata of each core position (chunks 2..12) and the block entities, in a fixed order.
   *  Returns the SHA-256 of that stream. Reads from the loadedChunks objects, without side effects. */
  static String dumpBlocks(File f) throws Exception {
    int h = world.getHeightBlocks();
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    Map<Integer, int[]> first = new HashMap<>();
    try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
        new DigestOutputStream(new GZIPOutputStream(new FileOutputStream(f), 1 << 16), md), 1 << 16))) {
      for (int ix = CORE0; ix <= CORE1; ix++) for (int iz = CORE0; iz <= CORE1; iz++) {
        Chunk c = read(ix, iz);
        if (c == null) throw new AssertionError("missing core chunk " + ix + "," + iz);
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) for (int y = 0; y < h; y++) {
          int id = c.getBlockID(lx, y, lz);
          out.writeShort(id);
          out.writeByte(c.getBlockMetadata(lx, y, lz));
          if (id != 0 && !first.containsKey(id)) first.put(id, new int[]{(ZX0 + ix) * 16 + lx, y, (ZZ0 + iz) * 16 + lz});
        }
      }
      TreeSet<String> entities = new TreeSet<>();
      for (int ix = CORE0; ix <= CORE1; ix++) for (int iz = CORE0; iz <= CORE1; iz++) {
        Chunk c = read(ix, iz);
        for (TileEntity te : c.tileEntityMap.values())
          entities.add(te.tilePos.x + "," + te.tilePos.y + "," + te.tilePos.z + "," + te.getClass().getName());
      }
      out.writeInt(entities.size());
      for (String e : entities) out.writeUTF(e);
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : md.digest()) hex.append(String.format("%02x", b));

    // blocks without collision (plants...) that appear, evaluated where they first appear.
    // getCollisionAABB reads through World, which may reach provideChunk and decorate silently: afterwards it is checked
    // that neither the decoration signature nor the zone blocks changed (if they did, compare fails).
    String signatureBefore = signature();
    long blocksBefore = blocksHash();
    TreeSet<Integer> noCollisionIds = new TreeSet<>();
    for (Map.Entry<Integer, int[]> e : first.entrySet()) {
      Block<?> b = Blocks.getBlock(e.getKey());
      if (b == null) continue;
      try {
        int[] p = e.getValue();
        if (b.getCollisionAABB(world, new TilePos(p[0], p[1], p[2])) == null) noCollisionIds.add(e.getKey());
      } catch (Throwable t) {
        collisionErrors++;
      }
    }
    StringBuilder s = new StringBuilder();
    for (int id : noCollisionIds) { if (s.length() > 0) s.append(','); s.append(id); }
    noCollision = s.toString();
    collisionChangedZone = !signatureBefore.equals(signature()) || blocksBefore != blocksHash();
    return hex.toString();
  }

  // ------------------------------------------------------------------ compare

  static final class Dump {
    final Map<String, String> kv = new HashMap<>();
    short[] ids; byte[] meta; TreeSet<String> entities = new TreeSet<>();
    String get(String k) {
      String v = kv.get(k);
      if (v == null) throw new AssertionError("missing '" + k + "' in the dump");
      return v;
    }
    int[] ints(String k) {
      String[] p = get(k).split(",");
      int[] r = new int[p.length];
      for (int i = 0; i < p.length; i++) r[i] = Integer.parseInt(p[i].trim());
      return r;
    }
  }

  static Dump readDump(File dir, String testCase, boolean withBlocks) throws Exception {
    Dump v = new Dump();
    File t = new File(dir, testCase + ".txt");
    if (!t.exists()) throw new AssertionError("missing dump of case " + testCase + " (" + t + ")");
    for (String l : Files.readAllLines(t.toPath(), StandardCharsets.UTF_8)) {
      int i = l.indexOf('=');
      if (i > 0) v.kv.put(l.substring(0, i), l.substring(i + 1));
    }
    if (!withBlocks) return v;
    int h = Integer.parseInt(v.get("height"));
    int total = CORE_SIZE * CORE_SIZE * 256 * h;
    v.ids = new short[total];
    v.meta = new byte[total];
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(
        new GZIPInputStream(new FileInputStream(new File(dir, testCase + ".bin.gz")), 1 << 16), 1 << 16))) {
      for (int i = 0; i < total; i++) { v.ids[i] = in.readShort(); v.meta[i] = in.readByte(); }
      int ne = in.readInt();
      for (int i = 0; i < ne; i++) v.entities.add(in.readUTF());
    }
    return v;
  }

  static Set<Integer> group(Dump... vs) {
    Set<Integer> g = new HashSet<>();
    for (Block<?> b : new Block<?>[]{Blocks.SAND, Blocks.GRAVEL, Blocks.FLUID_WATER_FLOWING, Blocks.FLUID_WATER_STILL,
        Blocks.FLUID_LAVA_FLOWING, Blocks.FLUID_LAVA_STILL, Blocks.OBSIDIAN, Blocks.COBBLE_STONE}) g.add(b.id());
    for (Dump v : vs) {
      String s = v.get("noCollision");
      if (!s.isEmpty()) for (String p : s.split(",")) g.add(Integer.parseInt(p));
    }
    g.remove(0);
    return g;
  }

  /** Positions that differ between two dumps, split into the reported group (sand, gravel, fluids,
   *  obsidian, loose stone, blocks without collision, and everything above them in their column) and the rest. */
  static long[] differences(Dump a, Dump b) {
    int h = Integer.parseInt(a.get("height"));
    Set<Integer> g = group(a, b);
    long reported = 0, rest = 0;
    int columns = CORE_SIZE * CORE_SIZE * 256;
    for (int c = 0; c < columns; c++) {
      int base = c * h;
      boolean marked = false;
      for (int y = 0; y < h; y++) {
        int i = base + y;
        if (!marked && (g.contains((int) a.ids[i]) || g.contains((int) b.ids[i]))) marked = true;
        if (a.ids[i] != b.ids[i] || a.meta[i] != b.meta[i]) { if (marked) reported++; else rest++; }
      }
    }
    return new long[]{reported, rest};
  }

  static void compare(File dir) throws Exception {
    List<String> failures = new ArrayList<>();
    Dump a = readDump(dir, "A", true), ap = readDump(dir, "Ap", true), b = readDump(dir, "B", true);
    Dump t = readDump(dir, "T", false), m = readDump(dir, "M", false);
    Dump vc = readDump(dir, "C", true), vd = readDump(dir, "D", true), ve = readDump(dir, "E", false);
    Dump vf = readDump(dir, "F", false), vg = readDump(dir, "G", false);
    Map<String, Dump> all = new java.util.LinkedHashMap<>();
    all.put("A", a); all.put("Ap", ap); all.put("B", b); all.put("M", m);
    all.put("C", vc); all.put("D", vd); all.put("E", ve); all.put("F", vf); all.put("G", vg);

    // criterion 1: every decoratable decorated and each position with exactly one real decoration
    for (Map.Entry<String, Dump> e : all.entrySet()) {
      int[] d = e.getValue().ints("decorations");
      String pop = e.getValue().get("pop");
      int undecorated = 0, different = 0;
      for (int iz = 1; iz < N - 1; iz++) for (int ix = 1; ix < N - 1; ix++) {
        int i = iz * N + ix;
        if (pop.charAt(i) != '1') undecorated++;
        if (d[i] != 1) different++;
      }
      System.out.println("criterion 1 " + e.getKey() + ": undecorated decoratables=" + undecorated + ", positions with != 1 decoration=" + different);
      if (undecorated > 0) failures.add("criterion 1 " + e.getKey() + ": " + undecorated + " undecorated decoratables");
      if (different > 0) failures.add("criterion 1 " + e.getKey() + ": " + different + " positions without exactly one decoration");
    }

    // non-emptiness: A has non-air blocks, or A == A-prime could pass with empty dumps
    if (Integer.parseInt(a.get("height")) <= 0) failures.add("A: core height <= 0");
    long nonAir = 0;
    for (short id : a.ids) if (id != 0) nonAir++;
    System.out.println("non-emptiness A: positions with a non-air block=" + nonAir);
    if (nonAir == 0) failures.add("A: the dump has no block that is not air");
    for (Map.Entry<String, Dump> e : all.entrySet()) {
      if (!"false".equals(e.getValue().get("collisionChangedZone")))
        failures.add(e.getKey() + ": the computation of blocks without collision (through World) changed the zone");
      if (!e.getValue().get("snapshotError").isEmpty())
        failures.add(e.getKey() + ": " + e.getValue().get("snapshotError").trim());
    }

    // criterion 2: plain BTA has a cascade
    int[] nest = a.ints("nested");
    int withCascade = 0;
    for (int iz = 1; iz < N - 1; iz++) for (int ix = 1; ix < N - 1; ix++) if (nest[iz * N + ix] > 0) withCascade++;
    System.out.println("criterion 2 A: positions decorated with depth > 1=" + withCascade + ", maximum depth=" + a.get("maxDepth"));
    if (withCascade == 0) failures.add("criterion 2: A has no position with depth > 1 (without a cascade the test proves nothing)");

    // T: smooth decoration is active headless
    System.out.println("T: throttleEnabled=" + t.get("throttle"));
    if (!"true".equals(t.get("throttle"))) failures.add("T: MobileWorldgen.throttleEnabled() is not true headless");
    if (!"true".equals(t.get("deferredNull"))) failures.add("T: the MobileWorldgen deferred list is not null without -Dbta.test.decoCounters");

    // A == A'
    String hA = a.get("hash"), hAp = ap.get("hash"), hB = b.get("hash");
    long[] dAAp = differences(a, ap);
    System.out.println("A against A': different positions=" + (dAAp[0] + dAAp[1]) + ", different entities=" + (a.entities.equals(ap.entities) ? 0 : 1));
    if (!hA.equals(hAp) || dAAp[0] + dAAp[1] != 0 || !a.entities.equals(ap.entities))
      failures.add("A != A': plain BTA is not deterministic (hash A=" + hA + ", A'=" + hAp + ", " + (dAAp[0] + dAAp[1]) + " different positions)");
    if (!a.get("decorations").equals(ap.get("decorations")) || !a.get("order").equals(ap.get("order")))
      failures.add("A != A': the order or the decorations per position differ");

    // B/A per group (printed to be read)
    long[] dAB = differences(a, b);
    long core = (long) CORE_SIZE * CORE_SIZE * 256 * Integer.parseInt(a.get("height"));
    System.out.println("B/A: reported group=" + dAB[0] + ", rest=" + dAB[1] + " (" + String.format("%.3f", dAB[1] * 1000.0 / core)
        + " per thousand of the core of " + core + " positions), block entities: A=" + a.entities.size() + " B=" + b.entities.size()
        + " in common=" + common(a.entities, b.entities));
    System.out.println("block entities in the whole zone: A=" + a.get("zoneEntities") + " B=" + b.get("zoneEntities"));
    System.out.println("hash A=" + hA);
    System.out.println("hash A'=" + hAp);
    System.out.println("hash B=" + hB);
    for (Map.Entry<String, Dump> e : all.entrySet())
      if ("CDEFG".contains(e.getKey())) System.out.println("hash " + e.getKey() + "=" + e.getValue().get("hash"));
    cascadeCriteria(a, b, vc, vd, ve, vf, vg, core, dAB, failures);
    // without the branch nothing is deferred (C, with -Dbta.deferCascade=false, still has a cascade)
    for (String k : new String[]{"A", "Ap", "B", "C"})
      if (!"0".equals(all.get(k).get("deferred"))) failures.add("criterion 3 " + k + ": " + all.get(k).get("deferred") + " deferred positions (must be 0)");
    if (Integer.parseInt(vc.get("maxDepth")) <= 1) failures.add("criterion 5 C: maximum depth " + vc.get("maxDepth") + " (with -Dbta.deferCascade=false there must be a cascade)");
    System.out.println("breakdown of the differing positions of the rest, B/A: " + breakdown(a, b));
    System.out.println("breakdown of the differing positions of the rest, D/A: " + breakdown(a, vd));
    System.out.println("real decoration order of the F/G assembly (ix,iz,depth), plain BTA: " + m.get("setupOrder"));
    System.out.println("times (generation ms): A=" + a.get("msGeneration") + " A'=" + ap.get("msGeneration")
        + " B=" + b.get("msGeneration") + " M=" + m.get("msGeneration"));

    if (!failures.isEmpty()) {
      System.out.println("FAILURES (" + failures.size() + "):");
      for (String f : failures) System.out.println("  - " + f);
      System.exit(1);
    }
    System.out.println("DecorationTest: OK");
  }

  static final int NEAR = 4;

  /** The 10 most frequent (block in x -> block in y) pairs among the differing positions of the "rest" group. */
  static String breakdown(Dump x, Dump y) {
    int h = Integer.parseInt(x.get("height"));
    Set<Integer> g = group(x, y);
    Map<String, Integer> pairs = new HashMap<>();
    int columns = CORE_SIZE * CORE_SIZE * 256;
    for (int c = 0; c < columns; c++) {
      boolean marked = false;
      for (int yy = 0; yy < h; yy++) {
        int i = c * h + yy;
        if (!marked && (g.contains((int) x.ids[i]) || g.contains((int) y.ids[i]))) marked = true;
        if (!marked && (x.ids[i] != y.ids[i] || x.meta[i] != y.meta[i]))
          pairs.merge(blockName(x.ids[i], x.meta[i]) + " -> " + blockName(y.ids[i], y.meta[i]), 1, Integer::sum);
      }
    }
    StringBuilder sb = new StringBuilder(System.lineSeparator());
    pairs.entrySet().stream().sorted((p, q) -> q.getValue() - p.getValue()).limit(10)
        .forEach(en -> sb.append("    ").append(en.getValue()).append("  ").append(en.getKey()).append(System.lineSeparator()));
    return sb.toString().stripTrailing();
  }

  static String blockName(short id, byte meta) {
    if (id == 0) return "air/" + meta;
    Block<?> b = Blocks.getBlock(id);
    String k = b == null ? "?" : b.getKey();
    return k + "(" + id + ")/" + meta;
  }

  /** Criteria 1 (observations of F and G), 3, 4 and 5. */
  static void cascadeCriteria(Dump a, Dump b, Dump c, Dump d, Dump e, Dump f, Dump g,
      long core, long[] dAB, List<String> failures) {
    // criterion 3: D and F, maximum depth 1 and at least one deferred with 8 neighbors; E, cascade only nearby
    for (Map.Entry<String, Dump> en : Map.of("D", d, "F", f).entrySet()) {
      int depthValue = Integer.parseInt(en.getValue().get("maxDepth"));
      int withEight = Integer.parseInt(en.getValue().get("deferredWithEight"));
      System.out.println("criterion 3 " + en.getKey() + ": maximum depth=" + depthValue + ", deferred=" + en.getValue().get("deferred")
          + ", deferred with 8 neighbors loaded=" + withEight);
      if (depthValue > 1) failures.add("criterion 3 " + en.getKey() + ": maximum depth " + depthValue + " (must be 1)");
      if (withEight == 0) failures.add("criterion 3 " + en.getKey() + ": no deferred position with its 8 neighbors loaded (the branch is not exercised)");
    }
    int[] nest = e.ints("nested");
    int near = 0, far = 0, center = N / 2;
    for (int iz = 1; iz < N - 1; iz++) for (int ix = 1; ix < N - 1; ix++) {
      if (nest[iz * N + ix] == 0) continue;
      if (Math.max(Math.abs(ix - center), Math.abs(iz - center)) <= NEAR) near++; else far++;
    }
    System.out.println("criterion 3 E: positions with depth > 1 at <= " + NEAR + " from the player=" + near + ", farther than " + NEAR + "=" + far
        + ", deferred=" + e.get("deferred"));
    if (near == 0) failures.add("criterion 3 E: no position with depth > 1 near the player (the nearness rule is not exercised)");
    if (far > 0) failures.add("criterion 3 E: " + far + " positions with depth > 1 farther than " + NEAR + " from chunkOver");

    // moment observations of F and G (criterion 1)
    for (Map.Entry<String, Dump> en : Map.of("F", f, "G", g).entrySet())
      if (!"ok".equals(en.getValue().get("obsSetup"))) failures.add(en.getKey() + ": insufficient assembly (no valid deferred position for N)");
    if ("ok".equals(f.get("obsSetup"))) {
      if (!"true".equals(f.get("obsFDecoratedAfterProvide"))) failures.add("F: N is not decorated right after provideChunk(N, true)");
      if (!"true".equals(f.get("obsFInQueue"))) failures.add("F: N is not in the MobileWorldgen queue before tick()");
    }
    if (!"ok".equals(g.get("obsGRequeueShortcut"))) failures.add("G: " + g.get("obsGRequeueShortcut"));
    if ("ok".equals(g.get("obsSetup"))) {
      if (!"true".equals(g.get("obsGUndecoratedBefore"))) failures.add("G: N was already decorated before tick()");
      if (!"true".equals(g.get("obsGDecoratedAfter"))) failures.add("G: N undecorated after tick()");
    }

    // criterion 4: D against A (reported group without a threshold; the rest <= max(B/A, 0.1 % of the core))
    long[] dAD = differences(a, d);
    long limit = Math.max(dAB[1], core / 1000);
    System.out.println("A/D: reported group=" + dAD[0] + ", rest=" + dAD[1] + " (limit " + limit + " = max(B/A rest " + dAB[1]
        + ", 0.1 % of the core))");
    if (dAD[1] > limit) failures.add("criterion 4: D against A has " + dAD[1] + " differing positions outside the reported group (limit " + limit + ")");
    System.out.println("ores by type, A: " + ores(a));
    System.out.println("ores by type, B: " + ores(b));
    System.out.println("ores by type, C: " + ores(c));
    System.out.println("ores by type, D: " + ores(d));
    int dropped = 0, added = 0;
    for (String x : a.entities) if (!d.entities.contains(x)) dropped++;
    for (String x : d.entities) if (!a.entities.contains(x)) added++;
    System.out.println("block entities dropped A/D: only in A=" + dropped + ", only in D=" + added + " (A=" + a.entities.size() + ", D=" + d.entities.size() + ")");
    System.out.println("block entities in the whole zone: C=" + c.get("zoneEntities") + " D=" + d.get("zoneEntities") + " E=" + e.get("zoneEntities")
        + " F=" + f.get("zoneEntities") + " G=" + g.get("zoneEntities"));

    // criterion 5: C against A (reported)
    long[] dAC = differences(a, c);
    System.out.println("A/C: reported group=" + dAC[0] + ", rest=" + dAC[1] + ", maximum depth A=" + a.get("maxDepth")
        + " C=" + c.get("maxDepth") + " D=" + d.get("maxDepth"));
  }

  /** Count of ore blocks by type (COAL, IRON...), adding up the base rocks. */
  static String ores(Dump v) {
    Map<String, Integer> byType = new java.util.TreeMap<>();
    Map<Integer, String> typeById = new HashMap<>();
    for (java.lang.reflect.Field fl : Blocks.class.getFields()) {
      if (!fl.getName().startsWith("ORE_") || !Block.class.isAssignableFrom(fl.getType())) continue;
      try {
        Block<?> bl = (Block<?>) fl.get(null);
        if (bl == null) continue;
        String n = fl.getName().substring(4);
        int i = n.lastIndexOf('_');
        typeById.put(bl.id(), i < 0 ? n : n.substring(0, i));
      } catch (IllegalAccessException ex) {
        throw new AssertionError(ex);
      }
    }
    for (short id : v.ids) {
      if (id == 0) continue;
      String type = typeById.get((int) id);
      if (type != null) byType.merge(type, 1, Integer::sum);
    }
    return byType.toString();
  }

  static int common(Set<String> x, Set<String> y) {
    int n = 0;
    for (String s : x) if (y.contains(s)) n++;
    return n;
  }

  public static void main(String[] args) throws Exception {
    String testCase = System.getProperty("test.case");
    String d = System.getProperty("test.dir");
    if (testCase == null || d == null) throw new IllegalArgumentException("missing -Dtest.case and -Dtest.dir");
    File dir = new File(d);
    if (!dir.isDirectory()) throw new IllegalArgumentException("directory does not exist: " + dir);
    if (testCase.equals("compare")) compare(dir);
    else if (Set.of("A", "Ap", "B", "M", "T", "C", "D", "E", "F", "G").contains(testCase)) runCase(testCase, dir);
    else throw new IllegalArgumentException("unknown case: " + testCase);
    System.exit(0);
  }
}
