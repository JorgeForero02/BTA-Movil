import java.lang.reflect.Field;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.container.LegacyRecipes;
import net.minecraft.client.render.terrain.AsyncChunkRenderer;

/**
 * With synchronous mesh building (-Dbta.asyncMesh=false, what the "Background Chunk Building" option
 * switched off gives) the console recipes are also precomputed when entering a world:
 * AsyncChunkRenderer.drainCompleted builds LegacyRecipes in the background the first time it sees a world, and
 * does not do it again for the same world.
 */
public class SyncRecipesTest {
  static void fail(String m) {
    System.out.println("SyncRecipesTest: FAIL - " + m);
    System.exit(1);
  }

  public static void main(String[] a) throws Throwable {
    if (!"false".equals(System.getProperty("bta.asyncMesh"))) fail("it must be launched with -Dbta.asyncMesh=false");
    RealMeshesTest.boot(); // leaves Minecraft.INSTANCE set
    net.minecraft.core.item.Items.init();
    Object reg = new net.minecraft.core.data.registry.Registries();
    if (net.minecraft.core.data.registry.Registries.RECIPES == null) new net.minecraft.core.data.registry.recipe.RecipeRegistry();
    for (String f : new String[]{"/recipes/blast_furnace.json", "/recipes/furnace.json", "/recipes/trommel.json", "/recipes/workbench.json"})
      net.minecraft.core.data.DataLoader.loadRecipesFromFile(f);

    // an unconstructed client world (WorldClient through allocateInstance) as the current world
    Field fu = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); fu.setAccessible(true); Object u = fu.get(null);
    Object world = u.getClass().getMethod("allocateInstance", Class.class).invoke(u, net.minecraft.client.world.WorldClient.class);
    Field fi = Minecraft.class.getDeclaredField("INSTANCE"); fi.setAccessible(true);
    Minecraft mc = (Minecraft) fi.get(null);
    Field fw = Minecraft.class.getDeclaredField("currentWorld"); fw.setAccessible(true);
    fw.set(mc, world);

    if (LegacyRecipes.built() != null) fail("the list was already built before starting");

    AsyncChunkRenderer.drainCompleted(new ArrayList<>());

    Thread thread = null;
    for (Thread t : Thread.getAllStackTraces().keySet()) if ("BTA-ConsoleRecipes".equals(t.getName())) thread = t;
    if (thread != null) {
      thread.join(60_000);
      if (thread.isAlive()) fail("the BTA-ConsoleRecipes thread is still alive after 60 s");
    }
    if (LegacyRecipes.built() == null) fail("built() is null: the synchronous build did not precompute the recipes");

    // same world: it is not built again (warming false). The lock is taken on this thread.
    Field fwarm = LegacyRecipes.class.getDeclaredField("warming"); fwarm.setAccessible(true);
    boolean warming;
    synchronized (LegacyRecipes.class) {
      AsyncChunkRenderer.drainCompleted(new ArrayList<>());
      warming = fwarm.getBoolean(null);
    }
    if (warming) fail("the second call with the same world started the build again (warming)");

    System.out.println("SyncRecipesTest: OK - " + LegacyRecipes.built().size() + " entries precomputed with the synchronous build");
  }
}
