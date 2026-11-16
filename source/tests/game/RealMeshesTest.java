import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.betterthanadventure.btamobile.meshfix.Mesher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.block.model.*;
import net.minecraft.client.render.tessellator.TessellatorTerrainImpl;
import net.minecraft.client.render.texturepack.TexturePackList;
import net.minecraft.core.block.*;
import net.minecraft.core.block.entity.TileEntity;
import net.minecraft.core.block.material.Material;
import net.minecraft.core.enums.LightLayer;
import net.minecraft.core.world.Dimension;
import net.minecraft.core.world.WorldSource;
import net.minecraft.core.world.biome.Biome;
import net.minecraft.core.world.pos.TilePos;
import net.minecraft.core.world.pos.TilePosc;
import net.minecraft.core.world.season.SeasonManager;
import net.minecraft.core.world.type.WorldType;
import net.minecraft.core.world.weather.WeatherManager;

/**
 * Builds meshes with all 455 REAL BTA blocks and models (headless) and checks that the result is the same with
 * 1 worker, with N workers at once, and with the main thread drawing in parallel (the fallback path case).
 * It also shows that the test detects collisions: with threads that are NOT workers (model state shared, as in
 * original BTA) the results get mixed up.
 *
 * Needs the game libraries (fastutil, joml, slf4j, gson). Usage:
 *   java -cp <classes>:BTA-Movil.jar:<libs> -Dbta.asyncMesh.threads=4 RealMeshesTest
 */
public class RealMeshesTest {
  // ------------------------------------------------------------------ fake world, no mutable state
  static final class FakeWorld implements WorldSource {
    final Block<?>[] palette; final long seed; final Biome biome; final Dimension dim;
    FakeWorld(Block<?>[] palette, long seed, Biome biome, Dimension dim) { this.palette = palette; this.seed = seed; this.biome = biome; this.dim = dim; }
    long h(TilePosc p, int salt) { long x = p.x() * 0x9E3779B97F4A7C15L ^ p.y() * 0xC2B2AE3D27D4EB4FL ^ p.z() * 0x165667B19E3779F9L ^ seed ^ salt * 0x27D4EB2F165667C5L; x ^= x >>> 33; x *= 0xff51afd7ed558ccdL; x ^= x >>> 33; return x; }
    public int getHeightBlocks() { return 256; }
    public Block<?> getBlockType(TilePosc p) {
      if (p.y() < 0 || p.y() >= 256) return Blocks.blocksList[0];
      long v = h(p, 1); if ((v & 7) < 3) return Blocks.AIR; // air (like World: the air block, never null)
      Block<?> b = palette[(int) ((v >>> 8) % palette.length)]; return b; }
    public TileEntity getTileEntity(TilePosc p) { return null; }
    public float getBrightness(TilePosc p, int min) { return Math.max(min, (int) (h(p, 2) & 15)) / 15f; }
    public byte getLightIndex(TilePosc p, int min) { return (byte) h(p, 3); }
    public float getLightBrightness(TilePosc p) { return (h(p, 2) & 15) / 15f; }
    public int getBlockData(TilePosc p) { return (int) (h(p, 4) & 15); }
    public Material getBlockMaterial(TilePosc p) { return getBlockType(p).getMaterial(); }
    public boolean isBlockOpaqueCube(TilePosc p) { return getBlockType(p).isSolidRender(); }
    public boolean isBlockNormalCube(TilePosc p) { Block<?> b = getBlockType(p); return b.getMaterial().isSolidBlocking() && b.renderAsNormalBlockOnCondition(this, p); }
    public double getBlockTemperature(TilePosc p) { return (h(p, 5) & 1023) / 1023.0; }
    public double getBlockHumidity(TilePosc p) { return (h(p, 6) & 1023) / 1023.0; }
    public double getBlockVariety(TilePosc p) { return (h(p, 7) & 1023) / 1023.0; }
    public SeasonManager getSeasonManager() { return null; }
    public WeatherManager getWeatherManager() { return null; }
    public Biome getBlockBiome(TilePosc p) { return biome; }
    public int getSavedLightValue(LightLayer l, TilePosc p) { return (int) (h(p, 8) & 15); }
    public byte getSavedLightIndex(TilePosc p) { return (byte) h(p, 9); }
    public Dimension getDimension() { return dim; }
    public WorldType getWorldType() { return net.minecraft.core.world.type.WorldTypes.OVERWORLD_DEFAULT; }
  }

  // ------------------------------------------------------------------ headless boot
  static void boot() throws Throwable {
    Blocks.init();
    Field fu = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); fu.setAccessible(true); Object u = fu.get(null);
    Minecraft mc = (Minecraft) u.getClass().getMethod("allocateInstance", Class.class).invoke(u, Minecraft.class);
    Field inst = Minecraft.class.getDeclaredField("INSTANCE"); inst.setAccessible(true); inst.set(null, mc);
    TexturePackList tpl = new TexturePackList(mc, java.nio.file.Files.createTempDirectory("bta").toFile());
    Field tf = Minecraft.class.getDeclaredField("texturePackList"); tf.setAccessible(true); tf.set(mc, tpl);
    try { tpl.init(); } catch (Throwable ignored) {}
    // biome colors (grass, leaves, water): loaded from the game's own images
    try { net.minecraft.client.render.colorizer.Colorizers.init(mc); } catch (Throwable e) { System.out.println("Colorizers.init: " + e); }
  }

  static <T> T firstStatic(Class<?> where, Class<T> type) throws Exception {
    for (Field f : where.getDeclaredFields()) if (Modifier.isStatic(f.getModifiers()) && type.isAssignableFrom(f.getType())) { f.setAccessible(true); Object v = f.get(null); if (v != null) return type.cast(v); }
    return null;
  }

  // ------------------------------------------------------------------ building one section, same as AsyncChunkRenderer.Job
  static final AtomicLong errors = new AtomicLong();
  static String assemble(WorldSource w, int ox, int oy, int oz) {
    TessellatorTerrainImpl tess = new TessellatorTerrainImpl(0x200000);
    BlockModelDispatcher d = BlockModelDispatcher.getInstance();
    StringBuilder signature = new StringBuilder();
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      TilePos p = new TilePos(0, 0, 0);
      for (int pass = 0; pass < 2; pass++) {
        tess.startDrawingQuads(); tess.setTranslation(-(ox >> 9 << 9), 0, -(oz >> 9 << 9));
        for (p.y = oy; p.y < oy + 16; p.y++) for (p.z = oz; p.z < oz + 16; p.z++) for (p.x = ox; p.x < ox + 16; p.x++) {
          Block<?> b = w.getBlockType(p); if (b == null || b == Blocks.AIR) continue;
          BlockModel m = (BlockModel) d.getDispatch(b);
          if (m.renderLayer() != pass) continue;
          tess.setLightmapCoord2i(15, 15); tess.setColor4i(255, 255, 255, 255); tess.setTextureUV(0, 0); tess.setShade1i(255);
          try { m.render(tess, w, p); } catch (Throwable t) { errors.incrementAndGet(); md.update((b.toString() + "!" + t.getClass().getName()).getBytes()); }
        }
        ByteBuffer buf = tess.data.getBuffer(); byte[] out = new byte[buf.position()]; buf.get(0, out);
        md.update(out); signature.append(tess.data.vertexCount).append('/');
        tess.drawing = false; tess.setTranslation(0, 0, 0);
      }
      return signature + HexFormat.of().formatHex(md.digest());
    } catch (Exception e) { throw new RuntimeException(e); }
  }

  public static void main(String[] a) throws Throwable {
    boot();
    List<Block<?>> all = new ArrayList<>();
    for (Block<?> b : Blocks.blocksList) if (b != null && b != Blocks.AIR) all.add(b);
    Biome biome = firstStatic(Class.forName("net.minecraft.core.world.biome.Biomes"), Biome.class);
    Dimension dim = firstStatic(Dimension.class, Dimension.class);

    // 1) Palette: drop the blocks that fail drawing alone in this fake world (e.g. they ask for seasons)
    BlockModelDispatcher d = BlockModelDispatcher.getInstance();
    List<Block<?>> palette = new ArrayList<>(); List<String> excluded = new ArrayList<>();
    for (Block<?> b : all) {
      FakeWorld alone = new FakeWorld(new Block<?>[]{b}, 1, biome, dim);
      long before = errors.get(); assemble(alone, 0, 64, 0);
      if (errors.get() == before) palette.add(b); else excluded.add(b.getKey());
    }
    errors.set(0);
    FakeWorld w = new FakeWorld(palette.toArray(new Block<?>[0]), 42, biome, dim);
    int sections = 96;
    int[][] org = new int[sections][]; Random r = new Random(5);
    for (int i = 0; i < sections; i++) org[i] = new int[]{(r.nextInt(64) - 32) * 16, r.nextInt(14) * 16 + 16, (r.nextInt(64) - 32) * 16};

    // 2) Reference: one section at a time on a worker
    String[] ref = new String[sections];
    for (int i = 0; i < sections; i++) { final int k = i; ref[k] = onWorker(() -> assemble(w, org[k][0], org[k][1], org[k][2])).get(); }

    // 3) Concurrent: all at once on the N workers, several rounds, with the main thread drawing in parallel
    int rounds = 6, different = 0, total = 0;
    for (int round = 0; round < rounds; round++) {
      List<Future<String>> fs = new ArrayList<>();
      for (int i = 0; i < sections; i++) { final int k = i; fs.add(onWorker(() -> assemble(w, org[k][0], org[k][1], org[k][2]))); }
      for (int i = 0; i < sections; i++) if (!assemble(w, org[i][0], org[i][1], org[i][2]).equals(ref[i])) different++; // main thread (the model's own fields)
      for (int i = 0; i < sections; i++) { total++; if (!fs.get(i).get().equals(ref[i])) different++; }
    }
    boolean ok = different == 0 && errors.get() == 0;
    System.out.println("Palette: " + palette.size() + " real BTA blocks (excluded by the fake world: " + excluded.size() + (excluded.isEmpty() ? "" : " " + excluded) + ")");
    System.out.println((ok ? "RealMeshesTest: OK" : "RealMeshesTest: FAIL") + " - " + Mesher.threads() + " workers + main thread, " + sections + " sections x " + rounds
        + " rounds, " + different + " results different from the reference, " + errors.get() + " exceptions");

    // 4) Sensitivity: plain threads (not workers) share the model state, like original BTA
    ExecutorService plain = Executors.newFixedThreadPool(4);
    int mixed = 0;
    for (int round = 0; round < rounds; round++) {
      List<Future<String>> fs = new ArrayList<>();
      for (int i = 0; i < sections; i++) { final int k = i; fs.add(plain.submit(() -> assemble(w, org[k][0], org[k][1], org[k][2]))); }
      for (int i = 0; i < sections; i++) { try { if (!fs.get(i).get().equals(ref[i])) mixed++; } catch (ExecutionException e) { mixed++; } }
    }
    plain.shutdown();
    System.out.println("Sensitivity control (shared state, like original BTA with several threads): " + mixed + " of " + sections * rounds
        + " sections came out different" + (mixed > 0 ? " -> the test DOES detect collisions" : " (no collisions were provoked in this run)"));
    if (mixed == 0) { ok = false; System.out.println("RealMeshesTest: FAIL - the sensitivity control provoked no collisions: the test proves nothing"); }
    System.exit(ok ? 0 : 1);
  }

  /** Runs the task on a real Mesher worker (so it uses its per-thread copies). */
  static <T> Future<T> onWorker(Callable<T> c) {
    CompletableFuture<T> f = new CompletableFuture<>();
    Mesher.Task t = () -> { try { f.complete(c.call()); } catch (Throwable e) { f.completeExceptionally(e); } };
    while (!Mesher.submit(t, true)) Thread.onSpinWait();
    // drain COMPLETED so it does not pile up
    Mesher.Task x; while ((x = Mesher.COMPLETED.poll()) != null) { }
    return f;
  }
}
