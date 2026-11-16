import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.betterthanadventure.btamobile.meshfix.Mesher;
import net.minecraft.client.world.WorldClient;
import net.minecraft.core.block.entity.TileEntity;
import net.minecraft.core.block.entity.TileEntityFurnace;
import net.minecraft.core.world.chunk.Chunk;
import net.minecraft.core.world.chunk.SnapshotChunkCache;
import net.minecraft.core.world.pos.ChunkPosc;
import net.minecraft.core.world.pos.ChunkTilePos;
import net.minecraft.core.world.pos.TilePos;

/** The mesher worker reads tile entities from a COPY of the chunk map, not from the live map
 *  (a HashMap that the main thread may resize while the worker walks it).
 *  We build a SnapshotChunkCache with a furnace in the map, remove the furnace from the LIVE map and read from a
 *  real Mesher worker: with -Dbta.tileSnapshot (the default) the furnace must be seen from the copy; with
 *  -Dbta.tileSnapshot=false, the old behavior: the live map, now without the furnace (null). */
public class TileSnapshotTest {
  /** World that can only return one chunk: World is abstract and allocateInstance does not run constructors. */
  public static class TestWorld extends WorldClient {
    Chunk chunk;
    TestWorld() { super(null, null, null, null); } // never runs (allocateInstance)
    @Override public Chunk getChunk(ChunkPosc p, boolean priority) { return chunk; }
    @Override public int getHeightBlocks() { return 128; }
  }

  static Object unsafe;
  @SuppressWarnings("unchecked")
  static <T> T create(Class<T> c) throws Exception {
    return (T) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, c);
  }

  /** Reads getTileEntity from a real Mesher worker; fails (exit 1) if it does not answer within 10 s. */
  static TileEntity onWorker(SnapshotChunkCache cache, TilePos p, String label) throws Exception {
    AtomicReference<Object> res = new AtomicReference<>();
    AtomicReference<Boolean> wasWorker = new AtomicReference<>(false);
    CountDownLatch done = new CountDownLatch(1);
    Mesher.Task t = () -> {
      try { wasWorker.set(Mesher.isWorker()); res.set(cache.getTileEntity(p)); }
      catch (Throwable e) { res.set(e); }
      finally { done.countDown(); }
    };
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!Mesher.submit(t, true)) {
      if (System.nanoTime() > deadline) fail(label + ": Mesher does not accept the task within 10 s");
      Thread.onSpinWait();
    }
    if (!done.await(10, TimeUnit.SECONDS)) fail(label + ": the worker does not answer within 10 s");
    if (!wasWorker.get()) fail(label + ": the task did not run on a WorkerThread");
    Object r = res.get();
    if (r instanceof Throwable) { ((Throwable) r).printStackTrace(System.out); fail(label + ": exception on the worker " + r); }
    return (TileEntity) r;
  }

  static void fail(String m) {
    System.out.println("TileSnapshotTest: FAIL - " + m);
    System.exit(1);
  }

  public static void main(String[] a) throws Throwable {
    boolean copy = Boolean.parseBoolean(System.getProperty("bta.tileSnapshot", "true"));
    RealMeshesTest.boot();
    Field fu = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); fu.setAccessible(true); unsafe = fu.get(null);

    Chunk chunk = create(Chunk.class);
    Map<ChunkTilePos, TileEntity> live = new HashMap<>();
    Field fm = Chunk.class.getDeclaredField("tileEntityMap"); fm.setAccessible(true); fm.set(chunk, live);
    TileEntityFurnace furnace = new TileEntityFurnace();
    TileEntityFurnace outside = new TileEntityFurnace();
    live.put(new ChunkTilePos(5, 64, 5), furnace);
    live.put(new ChunkTilePos(5, 71, 5), outside); // y = max + 1: just outside the area

    // The world returns the same chunk for cx = -1 and cx = 0: the furnace ends up copied at x = -11 (negative) and at x = 5.
    TestWorld world = create(TestWorld.class);
    world.chunk = chunk;
    SnapshotChunkCache cache = new SnapshotChunkCache(world, new TilePos(-16, 60, 0), new TilePos(15, 70, 15), true);
    TilePos where = new TilePos(5, 64, 5), whereNeg = new TilePos(-11, 64, 5), whereOutside = new TilePos(5, 71, 5);

    // 0) The copy is made at construction (main thread), not on the worker's first read.
    Field ft = SnapshotChunkCache.class.getDeclaredField("tileCopy"); ft.setAccessible(true);
    Object tileCopy = ft.get(cache);
    if (copy && tileCopy == null) fail("with -Dbta.tileSnapshot on, tileCopy must exist when the constructor returns");
    if (!copy && tileCopy != null) fail("with -Dbta.tileSnapshot=false tileCopy must be null");

    // 1) The main thread removes the furnace from the LIVE map BEFORE the worker's first read:
    //    a lazy copy (made on read) would no longer find it.
    live.remove(new ChunkTilePos(5, 64, 5));

    // 2) The worker reads: copy -> the furnace (also with negative x); no copy -> the live map, empty.
    TileEntity after = onWorker(cache, where, "after removing it");
    if (copy && after != furnace) fail("with -Dbta.tileSnapshot on the worker must read the COPY (the furnace); it read " + after + " (the live map)");
    if (!copy && after != null) fail("with -Dbta.tileSnapshot=false the worker must read the live map (null, as before); it read " + after);
    TileEntity neg = onWorker(cache, whereNeg, "negative x");
    if (copy && neg != furnace) fail("with the copy, the furnace at negative x (-11) must be seen; it read " + neg);
    if (!copy && neg != null) fail("without the copy, negative x reads the live map (null); it read " + neg);

    // 3) A furnace outside the area (y = max + 1) is not in the copy; without the copy the live map does give it.
    TileEntity f = onWorker(cache, whereOutside, "outside the area");
    if (copy && f != null) fail("with the copy, a furnace outside the area must not be seen; it read " + f);
    if (!copy && f != outside) fail("without the copy, the live map does have the furnace at y = max + 1; it read " + f);

    // 4) A spot without a tile entity still gives null.
    TileEntity empty = onWorker(cache, new TilePos(6, 64, 5), "empty spot");
    if (empty != null) fail("a spot without a tile entity must give null (gave " + empty + ")");

    // 5) Sensitivity: a cache built AFTER the removal does not see the furnace in any configuration.
    SnapshotChunkCache late = new SnapshotChunkCache(world, new TilePos(-16, 60, 0), new TilePos(15, 70, 15), true);
    TileEntity t = onWorker(late, where, "late cache");
    if (t != null) fail("a cache made after removing the furnace must not see it; it read " + t);

    System.out.println("TileSnapshotTest: OK (" + (copy ? "copy: the worker sees the furnace removed from the live map" : "no copy: the worker reads the live map") + ")");
    System.exit(0);
  }
}
