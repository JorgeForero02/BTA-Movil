import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.client.render.block.model.*;
import net.minecraft.client.render.tessellator.TessellatorTerrainImpl;
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
 * Water faces next to blocks (FluidInset): builds ONE water cell with real BTA models and looks at where its
 * vertices end up. With air around, as in BTA (0.0001 / 0.001); against ice, permaice or glass, 1/16 inward
 * into the water; under a block with water in the neighboring column, the top face no longer reaches 1.0.
 * Needs the same as RealMeshesTest and -Dbta.packedVertices=false (float positions).
 */
public class WaterTest {
  static final class TestWorld implements WorldSource {
    final Map<Long, Block<?>> blocks = new HashMap<>(); final Biome biome; final Dimension dim;
    TestWorld(Biome biome, Dimension dim) { this.biome = biome; this.dim = dim; }
    static long k(int x, int y, int z) { return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF); }
    TestWorld put(int x, int y, int z, Block<?> b) { blocks.put(k(x, y, z), b); return this; }
    public int getHeightBlocks() { return 256; }
    public Block<?> getBlockType(TilePosc p) { return blocks.getOrDefault(k(p.x(), p.y(), p.z()), Blocks.AIR); }
    public TileEntity getTileEntity(TilePosc p) { return null; }
    public float getBrightness(TilePosc p, int min) { return 1f; }
    public byte getLightIndex(TilePosc p, int min) { return (byte) 0xFF; }
    public float getLightBrightness(TilePosc p) { return 1f; }
    public int getBlockData(TilePosc p) { return 0; }
    public Material getBlockMaterial(TilePosc p) { return getBlockType(p).getMaterial(); }
    public boolean isBlockOpaqueCube(TilePosc p) { return getBlockType(p).isSolidRender(); }
    public boolean isBlockNormalCube(TilePosc p) { Block<?> b = getBlockType(p); return b.getMaterial().isSolidBlocking() && b.renderAsNormalBlockOnCondition(this, p); }
    public double getBlockTemperature(TilePosc p) { return 0.5; }
    public double getBlockHumidity(TilePosc p) { return 0.5; }
    public double getBlockVariety(TilePosc p) { return 0.5; }
    public SeasonManager getSeasonManager() { return null; }
    public WeatherManager getWeatherManager() { return null; }
    public Biome getBlockBiome(TilePosc p) { return biome; }
    public int getSavedLightValue(LightLayer l, TilePosc p) { return 15; }
    public byte getSavedLightIndex(TilePosc p) { return (byte) 0xFF; }
    public Dimension getDimension() { return dim; }
    public WorldType getWorldType() { return net.minecraft.core.world.type.WorldTypes.OVERWORLD_DEFAULT; }
  }

  /** Vertices (x, y, z) of the water cell at (0, 64, 0), relative to it. */
  static List<float[]> vertices(TestWorld w) {
    TessellatorTerrainImpl tess = new TessellatorTerrainImpl(0x10000);
    tess.startDrawingQuads(); tess.setTranslation(0, 0, 0);
    tess.setLightmapCoord2i(15, 15); tess.setColor4i(255, 255, 255, 255); tess.setTextureUV(0, 0); tess.setShade1i(255);
    TilePos p = new TilePos(0, 64, 0);
    ((BlockModel) BlockModelDispatcher.getInstance().getDispatch(w.getBlockType(p))).render(tess, w, p);
    ByteBuffer buf = tess.data.getBuffer().duplicate().order(java.nio.ByteOrder.nativeOrder());
    int n = tess.data.vertexCount, stride = buf.position() / Math.max(1, n);
    List<float[]> out = new ArrayList<>();
    for (int i = 0; i < n; i++) out.add(new float[]{buf.getFloat(i * stride), buf.getFloat(i * stride + 4) - 64, buf.getFloat(i * stride + 8)});
    tess.drawing = false;
    return out;
  }

  static int failures;
  /** Vertices with FluidContact in mode all / contact only / no contact: contact == expected and contact + rest == all. */
  static void contact(String name, TestWorld w, int expected) {
    net.betterthanadventure.btamobile.meshfix.FluidContact.mode(net.betterthanadventure.btamobile.meshfix.FluidContact.ALL);
    int all = vertices(w).size();
    net.betterthanadventure.btamobile.meshfix.FluidContact.mode(net.betterthanadventure.btamobile.meshfix.FluidContact.ONLY_CONTACT);
    int only = vertices(w).size();
    net.betterthanadventure.btamobile.meshfix.FluidContact.mode(net.betterthanadventure.btamobile.meshfix.FluidContact.NO_CONTACT);
    int rest = vertices(w).size();
    net.betterthanadventure.btamobile.meshfix.FluidContact.mode(net.betterthanadventure.btamobile.meshfix.FluidContact.ALL);
    check("contact " + name, only == expected && only + rest == all, "(all " + all + ", contact " + only + ", rest " + rest + ")");
  }
  static void check(String name, boolean ok, String detail) {
    System.out.println((ok ? "  OK    " : "  FAIL  ") + name + " " + detail);
    if (!ok) failures++;
  }
  static float min(List<float[]> v, int axis) { float m = Float.MAX_VALUE; for (float[] a : v) m = Math.min(m, a[axis]); return m; }
  static float max(List<float[]> v, int axis) { float m = -Float.MAX_VALUE; for (float[] a : v) m = Math.max(m, a[axis]); return m; }
  static boolean has(List<float[]> v, int axis, float value) { for (float[] a : v) if (Math.abs(a[axis] - value) < 1e-5f) return true; return false; }
  /** Water with water on 4 sides minus one (0 = -z, 1 = +z, 2 = -x, 3 = +x). */
  static TestWorld surroundedExcept(Biome biome, Dimension dim, Block<?> water, int without) {
    TestWorld m = new TestWorld(biome, dim).put(0, 64, 0, water);
    if (without != 0) m.put(0, 64, -1, water);
    if (without != 1) m.put(0, 64, 1, water);
    if (without != 2) m.put(-1, 64, 0, water);
    if (without != 3) m.put(1, 64, 0, water);
    return m;
  }
  static TestWorld surrounded(Biome biome, Dimension dim, Block<?> water) {
    return new TestWorld(biome, dim).put(0, 64, 0, water).put(1, 64, 0, water).put(-1, 64, 0, water).put(0, 64, 1, water).put(0, 64, -1, water);
  }

  public static void main(String[] a) throws Throwable {
    RealMeshesTest.boot();
    Biome biome = RealMeshesTest.firstStatic(Class.forName("net.minecraft.core.world.biome.Biomes"), Biome.class);
    Dimension dim = RealMeshesTest.firstStatic(Dimension.class, Dimension.class);
    Block<?> water = Blocks.FLUID_WATER_STILL;
    double gap = net.betterthanadventure.btamobile.meshfix.FluidInset.GAP;
    System.out.println("WaterTest (gap " + gap + "):");

    List<float[]> alone = vertices(new TestWorld(biome, dim).put(0, 64, 0, water));
    check("lone water, west side", Math.abs(min(alone, 0) - 1.0E-4f) < 1e-6 || Math.abs(min(alone, 0) + 1.0E-4f) < 1e-6, "(min x " + min(alone, 0) + ", BTA: +-0.0001)");
    check("lone water, bottom", has(alone, 1, 0.001f), "(bottom at y = 0.001, as in BTA)");

    for (Block<?> b : new Block<?>[]{Blocks.ICE, Blocks.PERMAICE, Blocks.GLASS}) {
      // water to the north, south and east: only the west face is left (against the block), besides top and bottom
      List<float[]> v = vertices(surrounded(biome, dim, water).put(-1, 64, 0, b));
      if (b.isSolidRender()) { // opaque (permaice): no face, as against stone
        check("against " + b.getKey() + " (opaque) on the west", !has(v, 0, (float) gap) && !has(v, 0, 1.0E-4f), "(no west face)");
        continue;
      }
      check("against " + b.getKey() + " on the west", has(v, 0, (float) gap) && !has(v, 0, 1.0E-4f), "(west face at x = " + gap + ", not at 0.0001)");
      // water on the 4 sides: only the top and the bottom, which touches the block
      List<float[]> d = vertices(surrounded(biome, dim, water).put(0, 63, 0, b));
      check("on " + b.getKey(), has(d, 1, (float) gap) && !has(d, 1, 0.001f), "(bottom at y = " + gap + ", not at 0.001)");
      List<float[]> t = vertices(new TestWorld(biome, dim).put(0, 64, 0, water).put(0, 65, 0, b).put(1, 65, 0, water).put(1, 64, 0, water));
      check("under " + b.getKey() + " with water in the neighboring column", max(t, 1) <= 1f - (float) gap + 1e-5, "(top at y " + max(t, 1) + ", BTA: 1.0)");
    }
    List<float[]> open = vertices(new TestWorld(biome, dim).put(0, 64, 0, water).put(1, 65, 0, water).put(1, 64, 0, water));
    check("no block above, neighboring column with water", Math.abs(max(open, 1) - 1f) < 1e-5, "(top at y " + max(open, 1) + ", same as BTA)");

    // edges of an inset face (north face against ice at z = -1), depending on the cell next to it
    float g = (float) gap;
    List<float[]> air = vertices(new TestWorld(biome, dim).put(0, 64, 0, water).put(0, 64, -1, Blocks.ICE));
    check("edges next to air: as in BTA", has(air, 0, -1.0E-4f) && has(air, 0, 1.0001f) && !has(air, 0, -g), "(x from -0.0001 to 1.0001)");
    List<float[]> step = vertices(new TestWorld(biome, dim).put(0, 64, 0, water).put(0, 64, -1, Blocks.ICE).put(-1, 64, 0, water).put(1, 64, 0, water));
    check("edges next to water without an inset face: stretched", has(step, 0, -g) && has(step, 0, 1 + g), "(x from -gap to 1 + gap)");
    List<float[]> continuous = vertices(new TestWorld(biome, dim).put(0, 64, 0, water).put(0, 64, -1, Blocks.ICE).put(-1, 64, 0, water).put(-1, 64, -1, Blocks.ICE));
    check("edge next to water with the same inset face: as in BTA (no double layer)", has(continuous, 0, -1.0E-4f) && !has(continuous, 0, -g), "(min x -0.0001)");
    List<float[]> corner = vertices(new TestWorld(biome, dim).put(0, 64, 0, water).put(0, 64, -1, Blocks.ICE).put(-1, 64, 0, Blocks.GLASS).put(0, 64, 1, water));
    check("corner with two blocks: north face shortened up to the west one", has(corner, 0, g) && !has(corner, 0, -1.0E-4f), "(north face from x = gap)");
    List<float[]> belowIce = vertices(surroundedExcept(biome, dim, water, 2).put(-1, 64, 0, Blocks.ICE).put(0, 63, 0, Blocks.ICE));
    check("west face with ice below: rises to the inset bottom", has(belowIce, 1, g) && !has(belowIce, 1, 0f), "(min y = gap)");
    List<float[]> belowWater = vertices(surroundedExcept(biome, dim, water, 2).put(-1, 64, 0, Blocks.ICE).put(0, 63, 0, water));
    check("west face with water below (no inset face): goes down", has(belowWater, 1, -g), "(min y = -gap)");
    List<float[]> aboveWater = vertices(surroundedExcept(biome, dim, water, 2).put(-1, 64, 0, Blocks.ICE).put(0, 65, 0, water));
    check("west face with water above (no inset face): goes up", has(aboveWater, 1, 1 + g), "(max y = 1 + gap)");
    List<float[]> surface = vertices(new TestWorld(biome, dim).put(0, 64, 0, water).put(-1, 64, 0, Blocks.ICE));
    check("open surface: does not stick out", max(surface, 1) < 0.95f, "(max y " + max(surface, 1) + ")");
    List<float[]> bottom = vertices(surrounded(biome, dim, water).put(0, 63, 0, Blocks.GLASS));
    check("bottom over glass, water around without an inset bottom: stretched", has(bottom, 0, -g) && has(bottom, 0, 1 + g) && has(bottom, 2, -g), "(bottom from -gap to 1 + gap)");
    List<float[]> side = vertices(new TestWorld(biome, dim).put(0, 64, 0, water).put(0, 63, 0, Blocks.GLASS));
    check("face against air over an inset bottom: ends at the bottom, does not hang", !has(side, 1, 0f) && has(side, 1, g), "(min y = gap)");
    List<float[]> ceiling = vertices(new TestWorld(biome, dim).put(0, 64, 0, water).put(0, 65, 0, Blocks.STONE).put(1, 65, 0, water).put(1, 64, 0, water));
    check("under stone with water in the neighboring column: top 1 - gap", Math.abs(max(ceiling, 1) - (1 - g)) < 1e-5, "(max y " + max(ceiling, 1) + ")");
    // contact faces (FluidContact): their own pass takes exactly the inset faces, the rest stays the same
    contact("next to ice", new TestWorld(biome, dim).put(0, 64, 0, water).put(-1, 64, 0, Blocks.ICE), 6);
    contact("on glass", new TestWorld(biome, dim).put(0, 64, 0, water).put(0, 63, 0, Blocks.GLASS), 6);
    contact("under tinted glass", new TestWorld(biome, dim).put(0, 64, 0, water).put(0, 65, 0, Blocks.GLASS_TINTED), 6);
    contact("next to permaice (no face)", new TestWorld(biome, dim).put(0, 64, 0, water).put(-1, 64, 0, Blocks.PERMAICE), 0);
    contact("lone water", new TestWorld(biome, dim).put(0, 64, 0, water), 0);
    contact("lava next to glass", new TestWorld(biome, dim).put(0, 64, 0, Blocks.FLUID_LAVA_STILL).put(-1, 64, 0, Blocks.GLASS), 0);
    System.out.println(failures == 0 ? "WaterTest: OK" : "WaterTest: " + failures + " FAILURES");
    if (failures != 0) System.exit(1);
  }
}
