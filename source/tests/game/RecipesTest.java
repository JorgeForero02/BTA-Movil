import java.util.List;
import net.minecraft.client.gui.container.LegacyRecipes;

/**
 * Console crafting recipe list with BTA's real registry (like RealMeshesTest, headless):
 * how long building it takes (the stall there used to be when opening the menu for the first time) and that
 * LegacyRecipes.warmUp builds it in the background: a call to all() in the meantime waits and gets the same list,
 * and afterwards returns instantly.
 */
public class RecipesTest {
  public static void main(String[] a) throws Throwable {
    RealMeshesTest.boot();
    net.minecraft.core.item.Items.init();
    Object reg = new net.minecraft.core.data.registry.Registries(); // registers the recipe types
    if (net.minecraft.core.data.registry.Registries.RECIPES == null) new net.minecraft.core.data.registry.recipe.RecipeRegistry();
    for (String f : new String[]{"/recipes/blast_furnace.json", "/recipes/furnace.json", "/recipes/trommel.json", "/recipes/workbench.json"})
      net.minecraft.core.data.DataLoader.loadRecipesFromFile(f);
    int registry = net.minecraft.core.data.registry.Registries.RECIPES.getAllCraftingRecipes().size();
    long t0 = System.nanoTime();
    LegacyRecipes.warmUp();                       // in the background, as when entering a world
    long t1 = System.nanoTime();
    List<LegacyRecipes.Entry> l = LegacyRecipes.all(); // the menu opens right away: waits for the same list
    long t2 = System.nanoTime();
    List<LegacyRecipes.Entry> l2 = LegacyRecipes.all();
    long t3 = System.nanoTime();
    boolean ok = l == l2 && l.size() > 500 && (t1 - t0) < 50_000_000L && several(l);
    System.out.println("RecipesTest: " + (ok ? "OK" : "FAIL") + " - " + registry + " workbench recipes, " + l.size() + " entries; warmUp returns in "
        + (t1 - t0) / 1000 + " us, build " + (t2 - t1) / 1_000_000 + " ms (in the background), then " + (t3 - t2) / 1000 + " us");
    boolean all = ok;

    // The precomputation does not touch BTA's recipe cache. The five cases always run and each one
    // says OK or FAIL; the exit code is 1 if any fails.
    all &= runCase(1, case1(l));
    all &= runCase(2, case2());
    all &= runCase(3, case3());
    all &= runCase(4, case4());
    all &= runCase(5, case5());
    if (!all) System.exit(1);
  }

  /** Reference measured with the code from before the change: number of entries and hash of the (key, output) sequence. */
  static final int N_REF = 605;
  static final long H_REF = -8881076205917278941L;

  static boolean runCase(int n, String error) {
    System.out.println("RecipesTest: case " + n + " " + (error == null ? "OK" : "FAIL - " + error));
    return error == null;
  }

  /** 1: the list is identical to the one from before the change (count and hash of the entry sequence). */
  static String case1(List<LegacyRecipes.Entry> l) {
    long h = hash(l);
    if (l.size() != N_REF || h != H_REF) return "list differs from the reference: " + l.size() + " entries, hash " + h + " (expected " + N_REF + ", " + H_REF + ")";
    return null;
  }

  static long hash(List<LegacyRecipes.Entry> l) {
    long h = 0;
    for (LegacyRecipes.Entry e : l) {
      h = 31 * h + e.key.hashCode();
      h = 31 * h + e.output.itemID;
      h = 31 * h + e.output.getMetadata();
      h = 31 * h + e.output.stackSize;
    }
    return h;
  }

  /** 2: warmFrom(list) does not go through the registry: BTA's recipe cache stays empty. */
  static String case2() throws Throwable {
    java.lang.reflect.Method wf;
    try { wf = LegacyRecipes.class.getMethod("warmFrom", List.class); }
    catch (NoSuchMethodException ex) { return "warmFrom(List) does not exist"; }
    List<?> list = net.minecraft.core.data.registry.Registries.RECIPES.getAllCraftingRecipes(); // before invalidating
    resetFields();
    net.minecraft.core.data.registry.Registries.RECIPES.invalidateCaches();
    wf.invoke(null, list);
    if (recipeCacheBuilt()) return "warmFrom rebuilt recipeCache";
    if (LegacyRecipes.built() == null) return "warmFrom left the list unbuilt";
    return null;
  }

  /** 3: the precomputation THREAD does not go through the registry (deterministic: the lock is taken on this thread). */
  static String case3() throws Throwable {
    String e = waitForThreads();
    if (e != null) return e;
    if (warming()) return "warming is still high before starting";
    resetFields();
    synchronized (LegacyRecipes.class) {
      LegacyRecipes.warmUp();                       // starts the thread, which waits for the lock
      net.minecraft.core.data.registry.Registries.RECIPES.invalidateCaches();
    }
    e = waitForThreads();
    if (e != null) return e;
    if (recipeCacheBuilt()) return "the precomputation thread rebuilt recipeCache";
    if (LegacyRecipes.built() == null) return "case 3: the thread did not build the list";
    return null;
  }

  /** 4: the signature is kept: the next all() returns the same list as the precomputation. */
  static String case4() throws Throwable {
    String e = waitForThreads();
    if (e != null) return e;
    resetFields();
    LegacyRecipes.warmUp();
    e = waitForThreads();
    if (e != null) return e;
    List<LegacyRecipes.Entry> b = LegacyRecipes.built();   // before all(): the other way round, all() rebuilds and always matches
    if (b == null) return "the precomputation left no list";
    if (LegacyRecipes.all() != b) return "all() rebuilt the list: the precomputation did not keep the signature";
    return null;
  }

  /** 5: early exit: with the list up to date, the precomputation does not rebuild it. */
  static String case5() throws Throwable {
    String e = waitForThreads();
    if (e != null) return e;
    List<LegacyRecipes.Entry> l = LegacyRecipes.all();
    LegacyRecipes.warmUp();
    e = waitForThreads();
    if (e != null) return e;
    if (LegacyRecipes.built() != l) return "the precomputation rebuilt a list that was already up to date";
    return null;
  }

  /** Bounded join of any BTA-ConsoleRecipes thread; error if it is still alive. */
  static String waitForThreads() throws InterruptedException {
    for (Thread t : Thread.getAllStackTraces().keySet())
      if ("BTA-ConsoleRecipes".equals(t.getName())) {
        t.join(60_000);
        if (t.isAlive()) return "the BTA-ConsoleRecipes thread is still alive after 60 s";
      }
    return null;
  }

  static boolean warming() throws Throwable {
    java.lang.reflect.Field f = LegacyRecipes.class.getDeclaredField("warming"); f.setAccessible(true);
    return f.getBoolean(null);
  }

  /** The four fields that hold the built list, reset to zero (otherwise the early exit skips the build). */
  static void resetFields() throws Throwable {
    set("all", null); set("builtFrom", null); set("builtSize", -1); set("builtSignature", 0L);
  }

  static void set(String name, Object v) throws Throwable {
    java.lang.reflect.Field f = LegacyRecipes.class.getDeclaredField(name); f.setAccessible(true);
    if (v instanceof Integer) f.setInt(null, (Integer) v);
    else if (v instanceof Long) f.setLong(null, (Long) v);
    else f.set(null, v);
  }

  static boolean recipeCacheBuilt() throws Throwable {
    java.lang.reflect.Field f = net.minecraft.core.data.registry.recipe.RecipeRegistry.class.getDeclaredField("recipeCache"); f.setAccessible(true);
    return f.get(net.minecraft.core.data.registry.Registries.RECIPES) != null;
  }

  /** A recipe with two selectable ingredients (bed: wool and planks): with two wools and two planks in the inventory,
   *  choosing the second of each makes the plan use exactly those. */
  static boolean several(List<LegacyRecipes.Entry> l) {
    for (LegacyRecipes.Entry e : l) {
      net.minecraft.core.data.registry.recipe.RecipeSymbol[] occ = e.occurrences();
      java.util.List<net.minecraft.core.data.registry.recipe.RecipeSymbol> kinds = new java.util.ArrayList<>();
      for (var s : occ) if (LegacyRecipes.resolve(s).size() > 1) { boolean fresh = true; for (var k : kinds) if (LegacyRecipes.sameItems(k, s)) fresh = false; if (fresh) kinds.add(s); }
      if (kinds.size() < 2 || !e.fits(3)) continue;
      net.minecraft.core.item.ItemStack[] inv = new net.minecraft.core.item.ItemStack[36];
      net.minecraft.core.item.ItemStack[] pins = new net.minecraft.core.item.ItemStack[kinds.size()];
      int slot = 0;
      for (int j = 0; j < kinds.size(); j++) {
        List<net.minecraft.core.item.ItemStack> r = LegacyRecipes.resolve(kinds.get(j));
        for (int o = 0; o < 2; o++) { var st = r.get(o).copy(); st.stackSize = 64; if (st.getMetadata() == -1) st.setMetadata(0); inv[9 + slot++] = st; }
        pins[j] = inv[9 + slot - 1];
      }
      for (var s : occ) { boolean covered = false; for (var k : kinds) if (LegacyRecipes.sameItems(k, s)) covered = true; if (!covered) { var r = LegacyRecipes.resolve(s); if (r.isEmpty()) { slot = -1; break; } var st = r.get(0).copy(); st.stackSize = 64; if (st.getMetadata() == -1) st.setMetadata(0); inv[9 + slot++] = st; } }
      if (slot < 0) continue;
      int[] free = LegacyRecipes.plan(e, inv), chosen = LegacyRecipes.plan(e, inv, kinds.toArray(new net.minecraft.core.data.registry.recipe.RecipeSymbol[0]), pins);
      if (free == null || chosen == null) { System.out.println("  recipe " + e.key + ": no plan"); return false; }
      for (int k = 0; k < occ.length; k++)
        for (int j = 0; j < kinds.size(); j++)
          if (LegacyRecipes.sameItems(occ[k], kinds.get(j)) && !LegacyRecipes.sameChoice(inv[chosen[k]], pins[j])) { System.out.println("  recipe " + e.key + ": does not use the chosen item"); return false; }
      System.out.println("  " + e.key + ": " + kinds.size() + " selectable ingredients, the plan uses the chosen one in each");
      return true;
    }
    System.out.println("  no recipe with two selectable ingredients");
    return false;
  }
}
