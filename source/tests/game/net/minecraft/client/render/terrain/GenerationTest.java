package net.minecraft.client.render.terrain;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import net.minecraft.client.render.tessellator.TessellatorTerrainImpl;
import net.minecraft.core.world.chunk.SnapshotChunkCache;

/** AsyncChunkRenderer.complete with the real code: a stale build (different generation, or the section
 *  dirtied while it was being built) does NOT leave the section clean. No GL or world: the terrain
 *  renderer is an empty subclass and the Job carries zero tile entities and no pass started. */
public class GenerationTest {
  /** Never built with its constructor (allocateInstance): only overrides what touches GL. */
  static final class Terrain extends TerrainRendererMultiDraw {
    private Terrain() { super(null); }
    @Override public void chunkDeleted(ChunkRendererMultiDraw c) { }
    @Override public void chunkRendered(ChunkRendererMultiDraw c, TessellatorTerrainImpl t, int pass) { }
    @Override public RenderRegion<TessellatorTerrainImpl.TerrainVertexConfig> getRenderRegionAtBlockPos(int x, int z) { return null; }
  }

  static Object unsafe;
  static Class<?> jobClass;
  static Constructor<?> jobConstructor;
  static Field jobField, generationField;
  static Method completeMethod;

  static Object create(Class<?> c) throws Exception {
    return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, c);
  }

  static Object newJob(AsyncChunkRenderer r, Object cache) throws Exception {
    return jobConstructor.newInstance(r, cache, generationField.getInt(r), 0, 0, 0, 16, 16, 16, 0.0, 0.0);
  }

  static boolean complete(AsyncChunkRenderer r, Object job) throws Exception {
    try { return (Boolean) completeMethod.invoke(r, job); }
    catch (InvocationTargetException e) { throw new IllegalStateException("complete threw " + e.getCause(), e.getCause()); }
  }

  public static void main(String[] a) throws Throwable {
    Field fu = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); fu.setAccessible(true); unsafe = fu.get(null);
    jobClass = Class.forName("net.minecraft.client.render.terrain.AsyncChunkRenderer$Job");
    jobConstructor = jobClass.getDeclaredConstructors()[0]; jobConstructor.setAccessible(true);
    jobField = AsyncChunkRenderer.class.getDeclaredField("job"); jobField.setAccessible(true);
    generationField = AsyncChunkRenderer.class.getDeclaredField("generation"); generationField.setAccessible(true);
    completeMethod = AsyncChunkRenderer.class.getDeclaredMethod("complete", jobClass); completeMethod.setAccessible(true);

    TerrainRendererMultiDraw t = (TerrainRendererMultiDraw) create(Terrain.class);
    Object cache = create(SnapshotChunkCache.class);
    AsyncChunkRenderer r = new AsyncChunkRenderer(t, null, new ArrayList<>(), 0, 0, 0, 16);
    int failedCase = 0;

    // 1: nobody touched the section since the Job was created -> clean
    r.setDirty(false);
    Object j = newJob(r, cache);
    jobField.set(r, j);
    if (!complete(r, j)) failedCase = 1;

    // 2: already dirty and with a build in progress: markIfMeshing raises the generation -> the result is stale
    if (failedCase == 0) {
      r.setDirty(true);
      j = newJob(r, cache);
      jobField.set(r, j);
      if (!r.isDirty() || jobField.get(r) == null) failedCase = 2;
      else if (!AsyncChunkRenderer.markIfMeshing(r)) failedCase = 2;
      else if (complete(r, j)) failedCase = 2;
    }

    // 3: dirtied after the Job was created -> stale
    if (failedCase == 0) {
      r.setDirty(false);
      j = newJob(r, cache);
      jobField.set(r, j);
      r.setDirty(true);
      if (complete(r, j)) failedCase = 3;
    }

    // 4: clean and with a Job: markIfMeshing does not touch the generation if it was not dirty -> still valid
    if (failedCase == 0) {
      r.setDirty(false);
      j = newJob(r, cache);
      jobField.set(r, j);
      if (r.isDirty() || AsyncChunkRenderer.markIfMeshing(r)) failedCase = 4;
      else if (!complete(r, j)) failedCase = 4;
    }

    System.out.println("GenerationTest: " + (failedCase == 0 ? "OK (4 cases: same generation, dirty with build, dirty afterwards, clean with build)" : "FAIL case " + failedCase));
    if (failedCase != 0) System.exit(1);
  }
}
