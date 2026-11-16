import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.gui.container.LegacyCrafting;
import net.minecraft.client.gui.container.LegacyRecipes;
import net.minecraft.core.InventoryAction;
import net.minecraft.core.data.registry.Registries;
import net.minecraft.core.data.registry.recipe.RecipeSymbol;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.item.ItemStack;
import net.minecraft.core.player.inventory.container.Container;
import net.minecraft.core.player.inventory.container.ContainerCrafting;
import net.minecraft.core.player.inventory.container.ContainerInventory;
import net.minecraft.core.player.inventory.menu.MenuAbstract;
import net.minecraft.core.player.inventory.menu.MenuCrafting;
import net.minecraft.core.player.inventory.menu.MenuInventory;
import net.minecraft.core.world.pos.TilePos;

/**
 * Console crafting (LegacyCrafting.craftOnce) neither duplicates nor loses items, using BTA's real registry
 * and menus, without a screen. For every LegacyRecipes.all() recipe that fits the grid (3x3 with MenuCrafting,
 * 2x2 with MenuInventory): puts 1 unit of the first option of each cell into an empty inventory, calls craftOnce
 * and requires OK, conservation by itemID:metadata (with the leftovers BTA's logic computes on a copy of the
 * grid), an empty grid and an empty cursor. Also: with a full inventory it makes no click and does not touch
 * the inventory, and the chosen ingredient is the one that gets spent.
 */
public class CraftingTest {
  static final int ENTRY_COUNT = 605;
  static final List<String> failures = new ArrayList<>();

  static void fail(String recipe, int size, String what) {
    String message = "recipe " + recipe + " (g=" + size + "): " + what;
    if (failures.size() < 20) System.out.println("  FAIL " + message);
    failures.add(message);
  }

  // ------------------------------------------------------------------ setup

  interface ClickCounter { int clicks(); }

  static final class CountingCraftingMenu extends MenuCrafting implements ClickCounter {
    int count;
    CountingCraftingMenu(ContainerInventory inventory, net.minecraft.core.world.World world) { super(inventory, world, new TilePos(0, 0, 0)); }
    @Override public ItemStack clicked(InventoryAction action, int[] args, Player player) { count++; return super.clicked(action, args, player); }
    @Override public int clicks() { return count; }
  }

  static final class CountingInventoryMenu extends MenuInventory implements ClickCounter {
    int count;
    CountingInventoryMenu(ContainerInventory inventory) { super(inventory); }
    @Override public ItemStack clicked(InventoryAction action, int[] args, Player player) { count++; return super.clicked(action, args, player); }
    @Override public int clicks() { return count; }
  }

  /** Fake menu for the grid copy: ContainerCrafting.removeItem calls the menu's slotsChanged. */
  static final class FakeMenu extends MenuAbstract {
    @Override public void slotsChanged(Container c) { }
    @Override public boolean stillValid(Player p) { return true; }
    @Override public it.unimi.dsi.fastutil.ints.IntList getMoveSlots(InventoryAction a, net.minecraft.core.player.inventory.slot.Slot s, int i, Player p) { return null; }
    @Override public it.unimi.dsi.fastutil.ints.IntList getTargetSlots(InventoryAction a, net.minecraft.core.player.inventory.slot.Slot s, int i, Player p) { return null; }
  }

  /** Player that is never constructed: only what crafting touches (inventory, world, stats). */
  static final class TestPlayer extends Player {
    TestPlayer() { super(null); }
    @Override public void animate4() { }
    @Override public void sendMessage(boolean b, net.minecraft.core.net.command.TextFormatting.Base c, String s) { }
    @Override public void sendMessageTranslated(boolean b, net.minecraft.core.net.command.TextFormatting.Base c, String s, String... a) { }
    @Override public void addStat(net.minecraft.core.achievement.stat.Stat s, int n) { }
  }

  static Object unsafe;
  static Object create(Class<?> c) throws Throwable { return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, c); }

  static final class Setup {
    final Player player;
    final ContainerInventory inventory;
    final MenuAbstract menu;
    Setup(int size, net.minecraft.core.world.World world) throws Throwable {
      player = (Player) create(TestPlayer.class);
      player.world = world;
      Field inventoryField = Player.class.getDeclaredField("inventory"); inventoryField.setAccessible(true);
      inventory = new ContainerInventory(player);
      inventoryField.set(player, inventory);
      menu = size == 3 ? new CountingCraftingMenu(inventory, world) : new CountingInventoryMenu(inventory);
      Field testPlayerField = LegacyCrafting.class.getDeclaredField("testPlayer"); testPlayerField.setAccessible(true);
      testPlayerField.set(null, player);
    }
    int clicks() { return ((ClickCounter) menu).clicks(); }
    Container grid() { return menu instanceof MenuCrafting ? ((MenuCrafting) menu).craftSlots : ((MenuInventory) menu).craftSlots; }
  }

  // ------------------------------------------------------------------ tallies by itemID:metadata

  // Conservation counts by itemID:metadata and ignores NBT (ice cream bucket charges), as the spec sets.
  static String key(ItemStack s) { return s.itemID + ":" + s.getMetadata(); }

  static void add(Map<String, Integer> map, ItemStack s, int sign) {
    if (s != null) map.merge(key(s), sign * s.stackSize, Integer::sum);
  }

  static Map<String, Integer> tally(Setup setup) {
    Map<String, Integer> result = new TreeMap<>();
    for (ItemStack s : setup.inventory.mainInventory) add(result, s, 1);
    for (ItemStack s : setup.inventory.armorInventory) add(result, s, 1);
    add(result, setup.inventory.getHeldItemStack(), 1);
    for (int i = 0; i < setup.grid().getContainerSize(); i++) add(result, setup.grid().getItem(i), 1);
    return result;
  }

  static void removeZeros(Map<String, Integer> map) { map.values().removeIf(v -> v == 0); }

  static String snapshot(Setup setup) {
    StringBuilder b = new StringBuilder();
    for (ItemStack s : setup.inventory.mainInventory) b.append(s == null ? "-" : key(s) + "x" + s.stackSize).append(',');
    for (ItemStack s : setup.inventory.armorInventory) b.append(s == null ? "-" : key(s) + "x" + s.stackSize).append(',');
    ItemStack held = setup.inventory.getHeldItemStack();
    return b.append("cursor=").append(held == null ? "-" : key(held) + "x" + held.stackSize).toString();
  }

  static boolean gridEmpty(Setup setup) {
    for (int i = 0; i < setup.grid().getContainerSize(); i++) if (setup.grid().getItem(i) != null) return false;
    return true;
  }

  // ------------------------------------------------------------------ ingredients

  /** 1 unit of the first option of each cell (wildcard -1 -> 0), grouped by itemID:metadata; null if some symbol does not resolve. */
  static Map<String, ItemStack> ingredients(LegacyRecipes.Entry e, int size, Map<String, Integer> counts) {
    Map<String, ItemStack> result = new TreeMap<>();
    for (RecipeSymbol s : e.occurrences()) {
      List<ItemStack> options = LegacyRecipes.resolve(s);
      if (options.isEmpty()) return null;
      ItemStack stack = options.get(0).copy();
      stack.stackSize = 1;
      if (stack.getMetadata() == -1) stack.setMetadata(0);
      result.putIfAbsent(key(stack), stack);
      counts.merge(key(stack), 1, Integer::sum);
    }
    return result;
  }

  /** Spreads the ingredients from slot 9 in stacks of at most getMaxStackSize; `each` = wanted stack size (0 = just what is needed). */
  static boolean place(Setup setup, Map<String, ItemStack> types, Map<String, Integer> counts, int each) {
    int slot = 9;
    for (Map.Entry<String, ItemStack> t : types.entrySet()) {
      int rest = each > 0 ? each : counts.get(t.getKey());
      while (rest > 0) {
        if (slot >= 36) return false;
        ItemStack s = t.getValue().copy();
        s.stackSize = Math.min(rest, s.getMaxStackSize());
        setup.inventory.mainInventory[slot++] = s;
        rest -= s.stackSize;
      }
    }
    return true;
  }

  /** Leftovers BTA leaves in the grid when taking the result: onCraftResult on a g x g copy with a fake menu. */
  static Map<String, Integer> leftovers(LegacyRecipes.Entry e, int size, String recipe) {
    ContainerCrafting gridCopy = new ContainerCrafting(new FakeMenu(), size, size);
    RecipeSymbol[] occurrences = e.occurrences();
    int[] cellIndexes = e.cells(size);
    for (int k = 0; k < occurrences.length; k++) {
      ItemStack stack = LegacyRecipes.resolve(occurrences[k]).get(0).copy();
      stack.stackSize = 1;
      if (stack.getMetadata() == -1) stack.setMetadata(0);
      gridCopy.setItem(cellIndexes[k], stack);
    }
    ItemStack[] returned = Registries.RECIPES.onCraftResult(gridCopy);
    if (returned != null) for (ItemStack d : returned) if (d != null) fail(recipe, size, "onCraftResult returned an item (the empty array was expected): " + key(d));
    Map<String, Integer> result = new TreeMap<>();
    for (int i = 0; i < size * size; i++) add(result, gridCopy.getItem(i), 1);
    return result;
  }

  // ------------------------------------------------------------------ cases

  /** Recipe with a normal inventory: OK + conservation + empty grid + empty cursor. true if it passes. */
  static boolean conserves(LegacyRecipes.Entry e, int size, net.minecraft.core.world.World world) throws Throwable {
    String recipe = e.key;
    Map<String, Integer> counts = new TreeMap<>();
    Map<String, ItemStack> types = ingredients(e, size, counts);
    if (types == null) { fail(recipe, size, "a symbol does not resolve to any item"); return false; }
    Setup setup = new Setup(size, world);
    if (!place(setup, types, counts, 0)) { fail(recipe, size, "the ingredients do not fit in the test inventory"); return false; }
    setup.inventory.mainInventory[35] = new ItemStack(net.minecraft.core.block.Blocks.COBBLE_STONE, 5); // foreign item: must not be touched
    Map<String, Integer> before = tally(setup);
    Map<String, Integer> inserted = new TreeMap<>(counts);
    Map<String, Integer> leftover = leftovers(e, size, recipe);
    String result = LegacyCrafting.craftOnce(setup.menu, e);
    Map<String, Integer> after = tally(setup);
    boolean ok = true;
    if (result != LegacyCrafting.OK) { fail(recipe, size, "craftOnce returned \"" + result + "\" instead of OK"); ok = false; }
    if (!gridEmpty(setup)) { fail(recipe, size, "grid not empty at the end"); ok = false; }
    if (setup.inventory.getHeldItemStack() != null) { fail(recipe, size, "cursor not empty at the end: " + key(setup.inventory.getHeldItemStack()) + "x" + setup.inventory.getHeldItemStack().stackSize); ok = false; }
    // after - (before - inserted + leftovers) must be exactly the recipe output, and nothing else
    Map<String, Integer> rest = new TreeMap<>(after);
    for (Map.Entry<String, Integer> x : before.entrySet()) rest.merge(x.getKey(), -x.getValue(), Integer::sum);
    for (Map.Entry<String, Integer> x : inserted.entrySet()) rest.merge(x.getKey(), x.getValue(), Integer::sum);
    for (Map.Entry<String, Integer> x : leftover.entrySet()) rest.merge(x.getKey(), -x.getValue(), Integer::sum);
    removeZeros(rest);
    boolean outputOk = rest.size() == 1;
    if (outputOk) {
      Map.Entry<String, Integer> only = rest.entrySet().iterator().next();
      String expected = e.output.itemID + ":";
      outputOk = only.getValue() == e.output.stackSize
          && (e.output.getMetadata() == -1 ? only.getKey().startsWith(expected) : only.getKey().equals(expected + e.output.getMetadata()));
    }
    if (!outputOk) {
      fail(recipe, size, "conservation broken: expected output " + key(e.output) + "x" + e.output.stackSize + " and nothing else, extra/missing " + rest
          + " | before " + before + " | inserted " + inserted + " | leftovers " + leftover + " | after " + after);
      ok = false;
    }
    return ok;
  }

  /** 0 = does not apply, 1 = passes, -1 = fails. Full inventory with the ingredients in stacks the plan does not empty and no room for the output. */
  static int fullInventory(LegacyRecipes.Entry e, int size, net.minecraft.core.world.World world) throws Throwable {
    String recipe = e.key;
    Map<String, Integer> counts = new TreeMap<>();
    Map<String, ItemStack> types = ingredients(e, size, counts);
    if (types == null) return 0;
    Setup setup = new Setup(size, world);
    for (ItemStack t : types.values()) if (t.itemID == e.output.itemID) return 0; // the output would fit in a stack of ingredients
    if (e.output.itemID == new ItemStack(net.minecraft.core.block.Blocks.COBBLE_STONE).itemID) return 0;
    for (Map.Entry<String, ItemStack> t : types.entrySet()) if (counts.get(t.getKey()) >= t.getValue().getMaxStackSize()) return 0; // the plan would empty the stack
    if (!place(setup, types, counts, 64)) return 0;
    for (int i = 0; i < 36; i++) if (setup.inventory.mainInventory[i] == null) setup.inventory.mainInventory[i] = new ItemStack(net.minecraft.core.block.Blocks.COBBLE_STONE, 64);
    String before = snapshot(setup);
    String result = LegacyCrafting.craftOnce(setup.menu, e);
    int outcome = 1;
    if (!"Inventory full".equals(result)) { fail(recipe, size, "full inventory: craftOnce returned \"" + result + "\" instead of \"Inventory full\""); outcome = -1; }
    if (setup.clicks() != 0) { fail(recipe, size, "clicks with a full inventory: " + setup.clicks() + " (0 expected)"); outcome = -1; }
    String after = snapshot(setup);
    if (!before.equals(after)) { fail(recipe, size, "full inventory: the inventory changed\n    before " + before + "\n    after  " + after); outcome = -1; }
    if (!gridEmpty(setup)) { fail(recipe, size, "full inventory: grid not empty"); outcome = -1; }
    return outcome;
  }

  /** Chosen ingredient: sticks with painted planks (or the first recipe with multi-option symbols) spend exactly what was chosen. */
  static boolean chosenIngredient(List<LegacyRecipes.Entry> all, net.minecraft.core.world.World world) throws Throwable {
    LegacyRecipes.Entry e = null;
    for (LegacyRecipes.Entry c : all) {
      if ("stick".equals(c.key) && c.fits(3) && LegacyRecipes.resolve(c.occurrences()[0]).size() > 2) { e = c; break; }
    }
    if (e == null) { fail("stick", 3, "there is no stick recipe with several plank options"); return false; }
    RecipeSymbol symbol = e.occurrences()[0];
    List<ItemStack> options = LegacyRecipes.resolve(symbol);
    int used = e.occurrences().length;
    boolean ok = true;
    // tried with the last and the second of the list (options.get(options.size() - 1) and options.get(1)): the plan without a choice cannot satisfy both
    for (ItemStack requested : new ItemStack[]{options.get(options.size() - 1), options.get(1)}) {
      Setup setup = new Setup(3, world);
      ItemStack a = options.get(0).copy(), b = options.get(1).copy(), c = options.get(options.size() - 1).copy();
      for (ItemStack s : new ItemStack[]{a, b, c}) { s.stackSize = 64; if (s.getMetadata() == -1) s.setMetadata(0); }
      setup.inventory.mainInventory[9] = a; setup.inventory.mainInventory[10] = b; setup.inventory.mainInventory[11] = c;
      ItemStack pin = requested.copy();
      if (pin.getMetadata() == -1) pin.setMetadata(0);
      pin.stackSize = 64;
      ItemStack chosen = null;
      for (ItemStack s : setup.inventory.mainInventory) if (s != null && LegacyRecipes.sameChoice(s, pin)) chosen = s;
      if (chosen == null) { fail("stick", 3, "chosen ingredient: the test did not find the chosen stack"); ok = false; continue; }
      Map<String, Integer> before = tally(setup);
      String result = LegacyCrafting.craftOnce(setup.menu, e, new RecipeSymbol[]{symbol}, new ItemStack[]{chosen});
      Map<String, Integer> after = tally(setup);
      if (result != LegacyCrafting.OK) { fail("stick", 3, "chosen ingredient: craftOnce returned \"" + result + "\""); ok = false; continue; }
      for (Map.Entry<String, Integer> x : before.entrySet()) {
        int spent = x.getValue() - after.getOrDefault(x.getKey(), 0);
        if (x.getKey().equals(key(a)) || x.getKey().equals(key(b)) || x.getKey().equals(key(c))) {
          int should = x.getKey().equals(key(chosen)) ? used : 0;
          if (spent != should) { fail("stick", 3, "chosen ingredient " + key(chosen) + ": of " + x.getKey() + " spent " + spent + " and should spend " + should); ok = false; }
        }
      }
    }
    return ok;
  }

  public static void main(String[] args) throws Throwable {
    RealMeshesTest.boot();
    net.minecraft.core.item.Items.init();
    new net.minecraft.core.data.registry.Registries(); // registers the recipe types
    if (Registries.RECIPES == null) new net.minecraft.core.data.registry.recipe.RecipeRegistry();
    for (String f : new String[]{"/recipes/blast_furnace.json", "/recipes/furnace.json", "/recipes/trommel.json", "/recipes/workbench.json"})
      net.minecraft.core.data.DataLoader.loadRecipesFromFile(f);
    Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); unsafeField.setAccessible(true); unsafe = unsafeField.get(null);
    net.minecraft.core.world.World world = (net.minecraft.core.world.World) create(net.minecraft.client.world.WorldClient.class);

    List<LegacyRecipes.Entry> all = LegacyRecipes.all();
    if (all.size() != ENTRY_COUNT) fail("(all)", 0, "LegacyRecipes.all() has " + all.size() + " entries and " + ENTRY_COUNT + " were expected");

    int[] fits = new int[4], passed = new int[4], fullTried = new int[4], fullPassed = new int[4];
    for (int size = 2; size <= 3; size++) {
      for (LegacyRecipes.Entry e : all) {
        if (!e.fits(size)) continue;
        fits[size]++;
        if (conserves(e, size, world)) passed[size]++;
        int full = fullInventory(e, size, world);
        if (full != 0) fullTried[size]++;
        if (full > 0) fullPassed[size]++;
      }
    }
    boolean chosenOk = chosenIngredient(all, world);
    if (!chosenOk && failures.isEmpty()) fail("stick", 3, "chosen ingredient");
    if (fits[3] != passed[3] || fits[2] != passed[2]) fail("(all)", 0, "totals: 3x3 " + passed[3] + "/" + fits[3] + ", 2x2 " + passed[2] + "/" + fits[2]);
    if (fits[3] != ENTRY_COUNT) fail("(all)", 3, "fit in 3x3: " + fits[3] + " and " + ENTRY_COUNT + " were expected");
    if (fits[2] != 245) fail("(all)", 2, "fit in 2x2: " + fits[2] + " and 245 were expected");
    if (fullTried[3] < 300 || fullTried[2] < 100) fail("(all)", 0, "too few full-inventory tests: 3x3 " + fullTried[3] + ", 2x2 " + fullTried[2]);

    boolean ok = failures.isEmpty();
    System.out.println("CraftingTest: " + (ok ? "OK" : "FAIL") + " - 3x3 " + passed[3] + "/" + fits[3] + ", 2x2 " + passed[2] + "/" + fits[2]);
    System.out.println("  full inventory (0 clicks, inventory intact): 3x3 " + fullPassed[3] + "/" + fullTried[3] + ", 2x2 " + fullPassed[2] + "/" + fullTried[2]
        + "; chosen ingredient: " + (chosenOk ? "OK" : "FAIL") + "; failures: " + failures.size());
    if (!ok) System.exit(1);
  }
}
