package net.minecraft.client.gui.container;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.data.registry.Registries;
import net.minecraft.core.data.registry.recipe.RecipeSymbol;
import net.minecraft.core.data.registry.recipe.entry.RecipeEntryCrafting;
import net.minecraft.core.data.registry.recipe.entry.RecipeEntryCraftingShaped;
import net.minecraft.core.data.registry.recipe.entry.RecipeEntryCraftingShapeless;
import net.minecraft.core.data.registry.recipe.entry.RecipeEntryCraftingWithTool;
import net.minecraft.core.item.IArmorItem;
import net.minecraft.core.item.Item;
import net.minecraft.core.item.ItemBow;
import net.minecraft.core.item.ItemFood;
import net.minecraft.core.item.ItemStack;
import net.minecraft.core.item.block.ItemBlock;
import net.minecraft.core.item.tool.ItemTool;
import net.minecraft.core.item.tool.ItemToolSword;

/**
 * Recipe side of the Legacy (console) crafting screen: which crafting recipes exist, which fit the
 * grid, which the player can make with what is in the inventory, and where each ingredient comes from.
 * Only recipes with a fixed layout are listed (shaped, shapeless, item + tool); dynamic ones
 * (dyeing, labels, repair, map copy...) still work in the classic grid.
 */
public final class LegacyRecipes {
  /** Console (Legacy Console Edition) crafting tabs, in their order. */
  public static final int STRUCTURES = 0, TOOLS = 1, FOOD = 2, ARMOUR = 3, MECHANISMS = 4, TRANSPORT = 5, DECORATION = 6, TAB_COUNT = 7;
  public static final String[] TAB_NAMES = {"Structures", "Tools & Weapons", "Food", "Armour", "Mechanisms", "Transport", "Decoration"};
  /** Item key (without "item."/"tile.") used as each tab's icon, if that recipe exists. */
  static final String[] TAB_ICONS = {"brick.clay", "tool.pickaxe.iron", "food.cake", "armor.chestplate.iron", "dust.redstone", "minecart", "painting"};

  /**
   * Console listing for BTA: {tab, group, key prefixes...}. A result goes to the rule with the longest
   * matching key prefix; buttons appear in this order inside each tab (console order, adapted to BTA:
   * sticks are a tool material here, books / paper and "unpacked" ingots and gems are decoration).
   */
  static final String[][] RULES = {
      // Structures
      {"0", "planks", "planks."},
      {"0", "ladder", "ladder."},
      {"0", "stone", "stone", "basalt", "granite", "limestone", "gloomstone", "netherrack", "permafrost", "slate.", "pillar.", "brimstone", "brimsand"},
      {"0", "cobble", "cobble."},
      {"0", "bricks", "brick."},
      {"0", "sandstone", "sandstone"},
      {"0", "compact", "block."},
      {"0", "earth", "sand", "gravel", "dirt", "soulsand", "ice.", "pumice", "sponge", "layer.ash", "layer.slate", "layer.snow", "bone_pile",
          "rubyglass.column", "rubyglass.growth", "sulfur", "ember", "glass.steel", "paper_wall"},
      {"0", "utility", "workbench", "furnace.", "trommel"},
      {"0", "chest", "chest."},
      {"0", "bed", "bed"},
      {"0", "fence", "fence."},
      {"0", "gate", "fencegate."},
      {"0", "door", "door."},
      {"0", "trapdoor", "trapdoor."},
      {"0", "stairs", "stairs."},
      {"0", "slab", "slab."},
      // Tools & Weapons
      {"1", "pickaxe", "tool.pickaxe"},
      {"1", "shovel", "tool.shovel"},
      {"1", "axe", "tool.axe"},
      {"1", "hoe", "tool.hoe"},
      {"1", "sword", "tool.sword"},
      {"1", "archery", "tool.bow", "ammo.", "handcannon"},
      {"1", "misc", "tool.shears", "tool.firestriker", "tnt", "gunpowder"},
      {"1", "rod", "tool.fishingrod"},
      {"1", "container", "bucket.", "bowl", "jar", "basket"},
      {"1", "lighting", "torch.coal", "brazier"},
      {"1", "location", "tool.clock", "tool.compass", "tool.calendar", "map", "rope", "label", "paintbrush"},
      {"1", "stick", "stick"},
      // Food
      {"2", "golden", "food.apple.gold"},
      {"2", "stew", "food.stew"},
      {"2", "cookie", "food.cookie"},
      {"2", "seeds", "seeds."},
      {"2", "pie", "food.pumpkin_pie"},
      {"2", "cake", "food.cake"},
      {"2", "sugar", "dust.sugar"},
      {"2", "dough", "dough"},
      // Armour
      {"3", "helmet", "armor.helmet"},
      {"3", "chestplate", "armor.chestplate"},
      {"3", "leggings", "armor.leggings"},
      {"3", "boots", "armor.boots"},
      {"3", "wolf", "armor.wolf"},
      {"3", "quiver", "armor.quiver"},
      // Mechanisms
      {"4", "activatable", "lamp.", "noteblock", "pumpkin.redstone"},
      {"4", "effect", "rubyglass.conduit"},
      {"4", "lever", "lever."},
      {"4", "redstone_torch", "torch.redstone"},
      {"4", "redstone", "dust.redstone", "block.redstone"},
      {"4", "utility", "repeater", "timer"},
      {"4", "sensor", "motionsensor", "matcher", "spikes"},
      {"4", "item_transport", "dispenser.", "activator."},
      {"4", "button", "button."},
      {"4", "pressure_plate", "pressureplate."},
      {"4", "piston", "piston."},
      // Transport
      {"5", "rail", "rail"},
      {"5", "minecart", "minecart"},
      {"5", "boat", "boat"},
      {"5", "seat", "seat"},
      // Decoration
      {"6", "glass", "glass.tinted", "mesh"},
      {"6", "wool", "wool"},
      {"6", "dye", "dye"},
      {"6", "carpet", "layer.leaves"},
      {"6", "misc", "jukebox", "cobweb", "glowstone", "pumpkin.carved"},
      {"6", "utility", "painting", "sign", "flag", "statue."},
      {"6", "library", "paper", "book", "bookshelf"},
      {"6", "unboxing", "ingot.", "diamond", "olivine", "quartz", "nethercoal", "rubyglass.crystal", "coal", "sugarcane"},
  };

  private static String[] ruleFor(String key) {
    String[] best = null;
    int bestLen = -1;
    for (String[] rule : RULES)
      for (int i = 2; i < rule.length; i++) {
        String prefix = rule[i];
        boolean isMatch = key.equals(prefix) || (key.startsWith(prefix) && (prefix.endsWith(".") || key.charAt(prefix.length()) == '.'));
        if (isMatch && prefix.length() > bestLen) { best = rule; bestLen = prefix.length(); }
      }
    return best;
  }

  /** Position of the result's group inside its tab (console order); unknown results go last. */
  static int orderOf(String key) {
    String[] rule = ruleFor(key);
    if (rule == null) return 1000;
    for (int i = 0; i < RULES.length; i++) if (RULES[i] == rule) return i;
    return 1000;
  }

  /** Material tier for ordering variants: wood, stone, iron, gold, diamond, steel; painted after plain. */
  static int materialRank(String key) {
    int rank = 1; // no material named: between wood and iron
    if (key.contains("wood") || key.contains("oak") || key.contains("planks") || key.contains("leather")) rank = 0;
    else if (key.contains("stone") || key.contains("cobble")) rank = 1;
    else if (key.contains("iron") || key.contains("chainlink")) rank = 2;
    else if (key.contains("gold")) rank = 3;
    else if (key.contains("diamond")) rank = 4;
    else if (key.contains("steel")) rank = 5;
    if (key.contains("painted")) rank += 10;
    return rank;
  }

  /** One listable recipe. cells[i] = symbol for grid cell i of a gridW x gridH layout (null = empty). */
  public static final class Entry {
    public final RecipeEntryCrafting<?, ?> recipe;
    public final ItemStack output;
    public final boolean shaped;
    public final int w, h;                // shaped size; shapeless: number of ingredients in w, h = 0
    public final RecipeSymbol[] symbols;  // shaped: w*h cells (nulls allowed); shapeless: the ingredients
    public final int category;
    public final String group;   // recipes with the same group share one button (variants)
    public final String key;     // item key without "item."/"tile."
    public final int order;      // group position inside the tab
    public final int rank;       // variant position inside the group (material tier)
    boolean dyed;                // made from BTA's dye / undye recipes (one entry per color)
    boolean undye;               // painted item + wet sponge -> plain item
    int creativeIndex = -1;      // position in BTA's creative menu (-1 = not listed there)
    int creativeLine = -1;       // the creative menu's family line (all stone things, all oak things...)

    Entry(RecipeEntryCrafting<?, ?> recipe, ItemStack output, boolean shaped, int w, int h, RecipeSymbol[] symbols) {
      this.recipe = recipe;
      this.output = output;
      this.shaped = shaped;
      this.w = w;
      this.h = h;
      this.symbols = symbols;
      this.key = keyOf(output);
      String[] rule = ruleFor(key);
      this.category = rule != null ? Integer.parseInt(rule[0]) : tabOf(output, key);
      this.group = rule != null ? "r:" + rule[0] + ":" + rule[1] : groupOf(output, key);
      this.order = orderOf(key);
      this.rank = materialRank(key);
    }

    public boolean fits(int grid) {
      if (shaped) return w <= grid && h <= grid;
      return symbols.length <= grid * grid;
    }

    private volatile int[] cells2, cells3;
    private volatile RecipeSymbol[] occurrencesCache;

    /** Grid cell (0..grid*grid-1) for each ingredient occurrence, parallel to occurrences(). Cached: do not modify. */
    public int[] cells(int grid) {
      int[] gridCells = grid == 2 ? cells2 : grid == 3 ? cells3 : null;
      if (gridCells != null) return gridCells;
      gridCells = buildCells(grid);
      if (grid == 2) cells2 = gridCells; else if (grid == 3) cells3 = gridCells;
      return gridCells;
    }

    private int[] buildCells(int grid) {
      List<Integer> out = new ArrayList<>();
      if (shaped) {
        for (int y = 0; y < h; y++)
          for (int x = 0; x < w; x++)
            if (symbols[x + y * w] != null) out.add(x + y * grid);
      } else {
        int cell = 0; // one cell per real ingredient, like occurrences()
        for (RecipeSymbol symbol : symbols) if (symbol != null) out.add(cell++);
      }
      int[] result = new int[out.size()];
      for (int i = 0; i < result.length; i++) result[i] = out.get(i);
      return result;
    }

    /** The symbol of each ingredient occurrence (one item each), parallel to cells(). Cached: do not modify. */
    public RecipeSymbol[] occurrences() {
      RecipeSymbol[] cached = occurrencesCache;
      if (cached != null) return cached;
      List<RecipeSymbol> out = new ArrayList<>();
      for (RecipeSymbol symbol : symbols) if (symbol != null) out.add(symbol);
      return occurrencesCache = out.toArray(new RecipeSymbol[0]);
    }
  }

  private static List<Entry> all;
  private static Object builtFrom;
  private static int builtSize = -1;
  private static long builtSignature;

  /** Cheap identity of the recipe list (a server can send a different list of the same size). */
  private static long signature(List<RecipeEntryCrafting<?, ?>> list) {
    long hash = list.size();
    int count = list.size(), step = Math.max(1, count / 16);
    for (int i = 0; i < count; i += step) hash = hash * 31 + System.identityHashCode(list.get(i));
    if (count > 0) hash = hash * 31 + System.identityHashCode(list.get(count - 1));
    return hash;
  }
  private static final Map<RecipeSymbol, List<ItemStack>> RESOLVED = new IdentityHashMap<>();

  private static volatile boolean warming;

  /**
   * Builds the recipe list on a background thread when a world is entered, so the first time the console
   * crafting menu opens it does not stall the game (the build checks every recipe against the registry:
   * {@link #shadowed}; ~0.2 s on a PC, more on a phone). The registry's lazy list and the creative listing are
   * built first on the render thread; the worker only reads. {@link #all}, {@link #resolve} and creativeOf are
   * synchronized, so opening the menu meanwhile just waits for the same build. The worker is handed that list and
   * builds from it ({@link #warmFrom}): it never calls the registry, so it cannot rebuild BTA's own recipe cache
   * (which a sync on the render thread may have invalidated meanwhile). -Dbta.recipeWarmup=false: off.
   */
  public static void warmUp() {
    if (warming || "false".equalsIgnoreCase(System.getProperty("bta.recipeWarmup"))) return;
    warming = true;
    // the registry's crafting list is built lazily and published without synchronization: build it here, on the
    // render thread, so the worker only reads a list that was complete before it started (Thread.start)
    List<RecipeEntryCrafting<?, ?>> recipes = null;
    try { if (Registries.RECIPES != null) recipes = Registries.RECIPES.getAllCraftingRecipes(); } catch (Throwable ignored) { }
    final List<RecipeEntryCrafting<?, ?>> given = recipes;
    // the creative listing is read with CreativeMenuContents.ADD_GAPS switched on for a moment: a static flag the
    // creative inventory also reads, so it is built here, on the render thread, never on the worker
    synchronized (LegacyRecipes.class) { try { creativeOf(new ItemStack(net.minecraft.core.block.Blocks.STONE)); } catch (Throwable ignored) { } }
    Thread worker = new Thread(() -> {
      try { if (given != null) warmFrom(given); } catch (Throwable ignored) { } finally { warming = false; }
    }, "BTA-ConsoleRecipes");
    worker.setDaemon(true);
    worker.setPriority(Thread.NORM_PRIORITY); // the menu may wait for it: no lower priority than the render thread
    worker.start();
  }

  /** The last list built (null if none yet), without checking the registry. */
  public static synchronized List<Entry> built() { return all; }

  /** All listable crafting recipes (rebuilt if the registry changes, e.g. joining a server). */
  public static synchronized List<Entry> all() {
    return build(Registries.RECIPES.getAllCraftingRecipes());
  }

  /**
   * Builds the list from {@code recipes} (taken from the registry by the caller) without asking the registry for
   * anything, and keeps it like {@link #all} does; does nothing if the list is already up to date.
   */
  public static synchronized void warmFrom(List<RecipeEntryCrafting<?, ?>> recipes) {
    build(recipes);
  }

  /** The one place that builds and keeps the list: returns the kept one if it is up to date. Caller holds the lock. */
  private static List<Entry> build(List<RecipeEntryCrafting<?, ?>> list) {
    Object registry = Registries.RECIPES;
    long currentSignature = signature(list);
    if (all != null && builtFrom == registry && builtSize == list.size() && builtSignature == currentSignature) return all;
    list = new ArrayList<>(list); // building: a fixed copy (a server may change the registry meanwhile)
    List<Entry> out = new ArrayList<>();
    java.util.Set<Long> outputs = new java.util.HashSet<>();
    List<Entry> dynamic = new ArrayList<>();
    for (RecipeEntryCrafting<?, ?> recipe : list) {
      try {
        Entry entry = toEntry(recipe);
        if (entry != null && entry.output != null && entry.output.getItem() != null) { out.add(entry); outputs.add(outKey(entry.output)); }
        else dynamic.addAll(dynamicEntries(recipe));
      } catch (Throwable ignored) { } // a recipe we cannot read is simply not listed
    }
    // dye / undye recipes work for any color: one entry per color, unless a normal recipe already makes it
    for (Entry entry : dynamic) {
      if (entry.output == null || entry.output.getItem() == null) continue;
      if (entry.undye || outputs.add(outKey(entry.output))) out.add(entry); // undyeing is its own recipe (painted + sponge)
    }
    RESOLVED.clear(); // the creative listing (item order) does not depend on the recipes: kept, built once
    for (Entry entry : out) {
      int[] creativePosition = creativeOf(entry.output);
      if (creativePosition != null) { entry.creativeIndex = creativePosition[0]; entry.creativeLine = creativePosition[1]; }
    }
    final List<RecipeEntryCrafting<?, ?>> fixed = list;
    out.removeIf(entry -> shadowed(entry, fixed));
    all = out;
    builtFrom = registry;
    builtSize = list.size();
    builtSignature = currentSignature;
    return out;
  }

  // ---------------------------------------------------------------- BTA's creative menu as reference

  private static Map<Long, int[]> creative;

  /** {index, family line} of an item in BTA's own creative menu listing, or null. */
  static synchronized int[] creativeOf(ItemStack stack) {
    if (creative == null) {
      Map<Long, int[]> positions = new HashMap<>();
      try {
        List<ItemStack> items = new ArrayList<>();
        boolean gaps = net.minecraft.core.player.inventory.CreativeMenuContents.ADD_GAPS;
        net.minecraft.core.player.inventory.CreativeMenuContents.ADD_GAPS = true;
        try { net.minecraft.core.player.inventory.CreativeMenuContents.populate(items); }
        finally { net.minecraft.core.player.inventory.CreativeMenuContents.ADD_GAPS = gaps; }
        // families = runs of items between gaps; a run that fills whole rows exactly (no gap was needed)
        // is several families of one row each, like the natural blocks
        List<int[]> runs = new ArrayList<>(); // {start, end) positions in items
        int start = -1;
        for (int i = 0; i <= items.size(); i++) {
          boolean item = i < items.size() && items.get(i) != null;
          if (item && start < 0) start = i;
          if (!item && start >= 0) { runs.add(new int[]{start, i}); start = -1; }
        }
        int line = 0, idx = 0;
        for (int[] run : runs) {
          int len = run[1] - run[0];
          boolean rows = len > 6 && len % 6 == 0 && run[0] % 6 == 0;
          for (int i = run[0]; i < run[1]; i++) {
            if (rows && i > run[0] && (i - run[0]) % 6 == 0) line++;
            ItemStack item = items.get(i);
            positions.putIfAbsent(((long) item.itemID << 20) | (item.getMetadata() & 0xFFFFF), new int[]{idx, line});
            positions.putIfAbsent(((long) item.itemID << 20) | 0xFFFFF, new int[]{idx, line});
            idx++;
          }
          line++;
        }
      } catch (Throwable ignored) {
        // no creative listing available: keep the rule-based order
      }
      creative = positions;
    }
    int[] position = creative.get(((long) stack.itemID << 20) | (stack.getMetadata() & 0xFFFFF));
    return position != null ? position : creative.get(((long) stack.itemID << 20) | 0xFFFFF);
  }

  /** One button of the crafting row: its key (to remember the chosen variant) and its variants. */
  public static final class Button {
    public final String key;
    public final List<Entry> variants = new ArrayList<>();
    Button(String key) { this.key = key; }
  }

  private static final String[] MATERIALS = {"oak", "stone", "slate", "basalt", "limestone", "granite", "permafrost", "netherrack",
      "gloomstone", "sandstone", "brimstone", "marble", "clay", "iron", "gold", "diamond", "steel", "lapis", "quartz", "olivine",
      "coal", "charcoal", "nethercoal", "glass", "wood", "leather"};

  /** The material named in a key (last match wins: "stairs.brick.iron" is iron), or "". */
  static String materialOf(String key) {
    String found = "";
    for (String part : key.split("\\.")) for (String material : MATERIALS) if (part.equals(material)) found = material;
    return found;
  }

  /**
   * The recipes of one tab arranged in buttons, in display order. In Structures the buttons follow
   * BTA's creative menu families (all the stone things in one button, all the oak things in another...;
   * a family too big for one button is split by material); painted versions get their own buttons with
   * the colors as variants; everything else keeps the console groups (all pickaxes, all stairs...) with
   * the materials as variants, in creative menu order.
   */
  public static List<Button> arrange(List<Entry> inTab) {
    // items that also come in dye colors (wool, lamps...): the plain one and its colors share a button
    java.util.Set<Integer> colored = new java.util.HashSet<>();
    for (Entry entry : inTab) if (entry.dyed && !entry.undye && entry.rank < 10) colored.add(entry.output.itemID);
    Map<String, Button> byKey = new LinkedHashMap<>();
    for (Entry entry : inTab) {
      String buttonKey;
      if (entry.undye) buttonKey = "undye";
      else if (entry.rank >= 10) buttonKey = entry.group + ":painted";
      else if (colored.contains(entry.output.itemID)) buttonKey = entry.group + ":item" + entry.output.itemID;
      else if (entry.dyed) buttonKey = entry.group + ":painted";
      else if (entry.category == STRUCTURES && entry.creativeLine >= 0) buttonKey = "c:" + entry.creativeLine;
      else buttonKey = entry.group;
      byKey.computeIfAbsent(buttonKey, Button::new).variants.add(entry);
    }
    List<Button> out = new ArrayList<>();
    for (Button button : byKey.values()) {
      if (button.key.startsWith("c:") && button.variants.size() > 12) { // split a big family by material
        Map<String, Button> sub = new LinkedHashMap<>();
        for (Entry entry : button.variants) sub.computeIfAbsent(button.key + ":" + materialOf(entry.key), Button::new).variants.add(entry);
        out.addAll(sub.values());
      } else out.add(button);
    }
    java.util.Comparator<Entry> byCreative = (a, b) -> { // listed in the creative menu first (keeps the order transitive)
      boolean aListed = a.creativeIndex >= 0, bListed = b.creativeIndex >= 0;
      if (aListed != bListed) return aListed ? -1 : 1;
      if (aListed && a.creativeIndex != b.creativeIndex) return Integer.compare(a.creativeIndex, b.creativeIndex);
      if (a.rank != b.rank) return Integer.compare(a.rank, b.rank);
      return Integer.compare(a.output.getMetadata(), b.output.getMetadata());
    };
    for (Button button : out) button.variants.sort(byCreative);
    out.sort((a, b) -> {
      Entry aHead = a.variants.get(0), bHead = b.variants.get(0);
      boolean aCreative = a.key.startsWith("c:"), bCreative = b.key.startsWith("c:");
      boolean aPainted = a.key.endsWith(":painted"), bPainted = b.key.endsWith(":painted");
      if (aPainted != bPainted) return aPainted ? 1 : -1;                 // painted buttons last
      if (aCreative && bCreative) return Integer.compare(aHead.creativeIndex, bHead.creativeIndex);
      if (aCreative != bCreative) return aCreative ? -1 : 1;                 // creative families first, then the console groups
      if (aHead.order != bHead.order) return Integer.compare(aHead.order, bHead.order);
      return Integer.compare(Math.max(0, aHead.creativeIndex), Math.max(0, bHead.creativeIndex));
    });
    return out;
  }

  /**
   * True if, with this recipe's ingredients in the grid, the game makes something else: another recipe
   * that takes the same items wins (for example 1 slate gives a slate button, never the slate layers).
   * Such a recipe can never be made in BTA, so it is not listed. Unknown (no result) = keep it.
   */
  static boolean shadowed(Entry entry, List<RecipeEntryCrafting<?, ?>> recipes) {
    try {
      int[] cells = entry.cells(3);
      RecipeSymbol[] symbolOccurrences = entry.occurrences();
      int fills = 1;
      for (RecipeSymbol occurrence : symbolOccurrences) fills = Math.max(fills, Math.min(16, resolve(occurrence).size()));
      for (int fillIndex = 0; fillIndex < fills; fillIndex++) { // try each option of the group ingredients (any planks...)
        net.minecraft.core.player.inventory.container.ContainerCrafting grid =
            new net.minecraft.core.player.inventory.container.ContainerCrafting(null, 3, 3);
        for (int cellIndex = 0; cellIndex < cells.length; cellIndex++) {
          List<ItemStack> options = resolve(symbolOccurrences[cellIndex]);
          if (options.isEmpty() || options.get(fillIndex % options.size()) == null) return false;
          ItemStack one = options.get(fillIndex % options.size()).copy(); // keeps a full bucket's state
          one.stackSize = 1;
          if (one.getMetadata() == -1) one.setMetadata(0);
          try { grid.setItem(cells[cellIndex], one); } catch (NullPointerException ignored) { } // no menu to notify
        }
        ItemStack made = null; // what findMatchingRecipe does, over the given list: the first recipe that matches
        for (RecipeEntryCrafting<?, ?> recipe : recipes)
          if (recipe.matches(grid)) { made = recipe.getCraftingResult(grid); break; }
        if (made == null) return false; // can't tell: keep it
        if (made.itemID == entry.output.itemID && (entry.output.getMetadata() == -1 || made.getMetadata() == entry.output.getMetadata())) return false;
      }
      return true; // every way of filling the grid makes something else
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static long outKey(ItemStack stack) { return ((long) stack.itemID << 20) | (stack.getMetadata() & 0xFFFFF); }

  /**
   * BTA's "dynamic" crafting recipes that the console screen can show as normal ones:
   *  - dyeing (planks, stairs, doors, wool, lamps... + one dye): one shapeless entry per color;
   *  - undyeing (painted item + wet sponge, the sponge stays): one entry, any color as input.
   * Labels, map copies and chainmail repair/scrap depend on the exact item used, so they stay in the
   * classic grid.
   */
  static List<Entry> dynamicEntries(RecipeEntryCrafting<?, ?> recipe) {
    List<Entry> out = new ArrayList<>();
    if (recipe instanceof net.minecraft.core.data.registry.recipe.entry.RecipeEntryDyeing) {
      net.minecraft.core.data.registry.recipe.entry.RecipeEntryDyeing dyeing = (net.minecraft.core.data.registry.recipe.entry.RecipeEntryDyeing) recipe;
      if (dyeing.inputSymbol == null || dyeing.output == null) return out;
      java.util.Set<Integer> seen = new java.util.HashSet<>();
      for (int dye = 0; dye < 16; dye++) {
        int meta = dyeing.getOutputMetaFromInputMeta(dye);
        if (!seen.add(meta)) continue;
        ItemStack result = new ItemStack(dyeing.output.getItem(), 1, meta);
        RecipeSymbol dyeSym = new RecipeSymbol(new ItemStack(net.minecraft.core.item.Items.DYE, 1, dye));
        Entry entry = new Entry(recipe, result, false, 2, 0, new RecipeSymbol[]{dyeing.inputSymbol, dyeSym});
        entry.dyed = true;
        out.add(entry);
      }
    } else if (recipe instanceof net.minecraft.core.data.registry.recipe.entry.RecipeEntryUndyeing) {
      net.minecraft.core.data.registry.recipe.entry.RecipeEntryUndyeing undyeing = (net.minecraft.core.data.registry.recipe.entry.RecipeEntryUndyeing) recipe;
      if (undyeing.inputSymbol == null || undyeing.output == null) return out;
      ItemStack result = new ItemStack(undyeing.output.getItem(), 1, undyeing.output.getMetadata());
      RecipeSymbol sponge = new RecipeSymbol(net.minecraft.core.block.Blocks.SPONGE_WET.getDefaultStack());
      Entry entry = new Entry(recipe, result, false, 2, 0, new RecipeSymbol[]{undyeing.inputSymbol, sponge});
      entry.dyed = true;
      entry.undye = true;
      out.add(entry);
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  static Entry toEntry(RecipeEntryCrafting<?, ?> recipe) {
    if (recipe instanceof RecipeEntryCraftingShaped) {
      RecipeEntryCraftingShaped shaped = (RecipeEntryCraftingShaped) recipe;
      RecipeSymbol[] input = (RecipeSymbol[]) shaped.getInput();
      if (input == null || input.length != shaped.recipeWidth * shaped.recipeHeight) return null;
      return new Entry(recipe, (ItemStack) shaped.getOutput(), true, shaped.recipeWidth, shaped.recipeHeight, input);
    }
    if (recipe instanceof RecipeEntryCraftingShapeless) {
      List<RecipeSymbol> input = (List<RecipeSymbol>) ((RecipeEntryCraftingShapeless) recipe).getInput();
      if (input == null || input.isEmpty()) return null;
      return new Entry(recipe, (ItemStack) recipe.getOutput(), false, input.size(), 0, input.toArray(new RecipeSymbol[0]));
    }
    if (recipe instanceof RecipeEntryCraftingWithTool) {
      RecipeEntryCraftingWithTool withTool = (RecipeEntryCraftingWithTool) recipe;
      if (withTool.toolToUse == null || withTool.inputSymbol == null || withTool.output == null) return null;
      return new Entry(recipe, withTool.output, false, 2, 0, new RecipeSymbol[]{withTool.toolToUse, withTool.inputSymbol});
    }
    return null;
  }

  static String keyOf(ItemStack out) {
    String key;
    try { key = out.getItem().getKey(); } catch (Throwable ignored) { key = null; }
    if (key == null) key = "id" + out.itemID;
    if (key.startsWith("item.")) key = key.substring(5);
    else if (key.startsWith("tile.")) key = key.substring(5);
    return key;
  }

  private static String first(String key) {
    int dot = key.indexOf('.');
    return dot < 0 ? key : key.substring(0, dot);
  }

  private static String second(String key) {
    String[] parts = key.split("\\.");
    return parts.length > 1 ? parts[1] : key;
  }

  /** Block logic name without "BlockLogic" (null for items that are not blocks). */
  static String logicOf(Item item) {
    if (!(item instanceof ItemBlock)) return null;
    try {
      String name = ((ItemBlock<?>) item).getBlock().getLogic().getClass().getSimpleName();
      return name.startsWith("BlockLogic") ? name.substring(10) : name;
    } catch (Throwable ignored) { return ""; }
  }

  private static boolean startsWithAny(String text, String... options) {
    if (text == null) return false;
    for (String option : options) if (text.startsWith(option)) return true;
    return false;
  }

  /** Which console tab a result goes to (by item / block type, like the console listing). */
  static int tabOf(ItemStack out, String key) {
    Item item = out == null ? null : out.getItem();
    if (item == null) return STRUCTURES;
    String cls = item.getClass().getSimpleName();
    String logic = logicOf(item);
    if (item instanceof IArmorItem || startsWithAny(cls, "ItemQuiver")) return ARMOUR;
    if (item instanceof ItemTool || item instanceof ItemToolSword || item instanceof ItemBow
        || startsWithAny(cls, "ItemTool", "ItemFishingRod", "ItemFireStriker", "ItemBucket", "ItemMap", "ItemHandCannon", "ItemPaintBrush", "ItemRope", "ItemJar")
        || startsWithAny(key, "ammo.", "tool.")) return TOOLS;
    if (item instanceof ItemFood || startsWithAny(cls, "ItemSeeds") || startsWithAny(key, "food.")) return FOOD;
    if (startsWithAny(cls, "ItemMinecart", "ItemBoat") || startsWithAny(logic, "Rail") || startsWithAny(key, "seat")) return TRANSPORT;
    if (startsWithAny(logic, "Button", "PressurePlate", "Lever", "Piston", "Dispenser", "Activator", "Note", "MotionSensor", "TNT",
        "TorchRedstone", "Redstone", "Lamp", "TrapDoor", "Matcher", "PumpkinRedstone", "Spikes", "Conduit", "Trommel")
        || startsWithAny(cls, "ItemDoor") || startsWithAny(key, "dust.redstone", "repeater", "timer")) return MECHANISMS;
    if (startsWithAny(logic, "Wool", "Torch", "Pumpkin", "Brazier", "Mesh", "Cobweb", "Jukebox", "Bookshelf", "LayerLeaves", "GlowStone", "GlassTinted")
        || startsWithAny(cls, "ItemSign", "ItemPainting", "ItemFlag", "ItemBed", "ItemStatue", "ItemDye", "ItemLabel")
        || startsWithAny(key, "basket")) return DECORATION;
    return STRUCTURES;
  }

  /** Recipes with the same group share one button; their results are the variants (up/down). */
  static String groupOf(ItemStack out, String key) {
    Item item = out.getItem();
    String logic = logicOf(item);
    if (logic != null) {
      String logicName = logic.replace("Painted", "").replace("Paintable", "").replace("PlanksOak", "");
      if (key.endsWith(".polished")) return "b:polished";
      if (logicName.isEmpty() || logicName.equals("AxisAligned")) return "b:" + first(key); // plain blocks: bricks together, storage blocks together...
      return "B:" + logicName;
    }
    String typeName = item.getClass().getSimpleName();
    if (typeName.startsWith("Item")) typeName = typeName.substring(4);
    typeName = typeName.replace("Painted", "").replace("Iron", "").replace("Steel", "").replace("Unloaded", "");
    if (typeName.equals("Armor")) return "armor:" + second(key);
    if (startsWithAny(key, "ingot.", "diamond", "olivine", "quartz", "nethercoal", "rubyglass.crystal", "coal")) return "i:minerals";
    if (typeName.isEmpty() || typeName.equals("Placeable") || typeName.equals("Food") || typeName.equals("Coal")) return "i:" + (typeName.equals("Placeable") || typeName.equals("Food") ? key : first(key));
    return "I:" + typeName;
  }

  /** Stacks a symbol accepts (cached; RecipeSymbol.resolve allocates every call). */
  public static synchronized List<ItemStack> resolve(RecipeSymbol symbol) {
    List<ItemStack> stacks = RESOLVED.get(symbol);
    if (stacks == null) {
      try { stacks = symbol.resolve(); } catch (Throwable ignored) { stacks = null; }
      stacks = stacks == null ? new ArrayList<>() : new ArrayList<>(stacks);
      // a recipe that wants a full bucket (milk, water, acid...) lists the bare bucket: show the full one
      for (int i = 0; i < stacks.size(); i++) {
        ItemStack bucket = stacks.get(i);
        try {
          if (bucket == null || !(bucket.getItem() instanceof net.minecraft.core.item.ItemBucket) || symbol.matches(bucket)) continue;
          for (net.minecraft.core.util.collection.NamespaceID id : net.minecraft.core.item.ItemBucket.getRegisteredStateIds()) {
            ItemStack full = bucket.copy();
            net.minecraft.core.item.ItemBucket.setState(full, id);
            if (symbol.matches(full)) { stacks.set(i, full); break; }
          }
        } catch (Throwable ignored) { }
      }
      RESOLVED.put(symbol, stacks);
    }
    return stacks;
  }

  /** Same result as s.matches(stack), but only runs the full check when the item id can match. */
  public static boolean matches(RecipeSymbol symbol, ItemStack stack) {
    if (symbol == null || stack == null) return false;
    List<ItemStack> resolved = resolve(symbol);
    boolean maybe = resolved.isEmpty(); // nothing resolved: let the symbol itself decide
    boolean damageable = false;
    try { damageable = stack.isItemStackDamageable(); } catch (Throwable ignored) { }
    for (ItemStack candidate : resolved) // quick check; tools keep their damage in the metadata, so only the item counts for them
      if (candidate != null && candidate.itemID == stack.itemID && (damageable || candidate.getMetadata() == -1 || candidate.getMetadata() == stack.getMetadata())) { maybe = true; break; }
    if (!maybe) return false;
    try { return symbol.matches(stack); } catch (Throwable ignored) { return false; } // the game's own rule (damage, meta...)
  }

  /**
   * Picks an inventory slot for every ingredient occurrence. inventory = the 36 inventory stacks.
   * Returns the source index per occurrence, or null if the ingredients are not there.
   * Small exact search (at most 9 occurrences): rarest ingredient first, backtracking when two
   * ingredients compete for the same items (e.g. "any planks" and "oak planks").
   */
  public static int[] plan(Entry entry, ItemStack[] inventory) { return plan(entry, inventory, (RecipeSymbol[]) null, null); }

  public static int[] plan(Entry entry, ItemStack[] inventory, RecipeSymbol pinSym, ItemStack pin) {
    return pin == null ? plan(entry, inventory) : plan(entry, inventory, new RecipeSymbol[]{pinSym}, new ItemStack[]{pin});
  }

  /** Same choice of ingredient: same item and metadata (only the item for tools, their metadata is their wear), and
   *  for buckets the same contents. */
  public static boolean sameChoice(ItemStack a, ItemStack b) {
    if (a == null || b == null || a.itemID != b.itemID) return false;
    boolean damageable = false;
    try { damageable = a.isItemStackDamageable(); } catch (Throwable ignored) { }
    return (damageable || a.getMetadata() == b.getMetadata()) && contents(a) == contents(b);
  }

  /** What a bucket holds (its state is not in the metadata), as a number; 0 for anything else. */
  public static int contents(ItemStack stack) {
    try {
      if (stack == null || !(stack.getItem() instanceof net.minecraft.core.item.ItemBucket)) return 0;
      Object state = net.minecraft.core.item.ItemBucket.getState(stack);
      return state == null ? 0 : state.toString().hashCode() | 1;
    } catch (Throwable ignored) { return 0; }
  }

  /**
   * Like plan(entry, inventory), but every ingredient that takes the same items as pinSyms[j] must be pins[j] (null: any):
   * the ingredients the player chose (which planks, which wool...).
   */
  public static int[] plan(Entry entry, ItemStack[] inventory, RecipeSymbol[] pinSyms, ItemStack[] pins) {
    if (pins == null) return planWith(entry, inventory, null, null);
    int[] sources = planWith(entry, inventory, pinSyms, pins);
    // a choice is a preference: when there is not enough of the chosen item, any of the accepted ones will do
    return sources != null ? sources : planWith(entry, inventory, null, null);
  }

  /** Only with the chosen items (no fallback): null when there is not enough of them. */
  public static int[] planChosen(Entry entry, ItemStack[] inventory, RecipeSymbol[] pinSyms, ItemStack[] pins) {
    return planWith(entry, inventory, pinSyms, pins);
  }

  private static int[] planWith(Entry entry, ItemStack[] inventory, RecipeSymbol[] pinSyms, ItemStack[] pins) {
    RecipeSymbol[] symbolOccurrences = entry.occurrences();
    int[] left = new int[inventory.length];
    for (int i = 0; i < inventory.length; i++) left[i] = inventory[i] == null ? 0 : inventory[i].stackSize;
    int[][] candidates = new int[symbolOccurrences.length][];
    Integer[] order = new Integer[symbolOccurrences.length];
    int[] total = new int[symbolOccurrences.length];
    for (int k = 0; k < symbolOccurrences.length; k++) {
      order[k] = k;
      int[] candidateSlots = new int[inventory.length];
      int candidateCount = 0;
      ItemStack pin = null;
      if (pins != null) for (int j = 0; j < pins.length && pin == null; j++) if (pins[j] != null && sameItems(symbolOccurrences[k], pinSyms[j])) pin = pins[j];
      for (int offset = 0; offset < inventory.length; offset++) {
        int i = slotOrder(offset, inventory.length);
        if (left[i] > 0 && matches(symbolOccurrences[k], inventory[i]) && (pin == null || sameChoice(inventory[i], pin))) {
          candidateSlots[candidateCount++] = i; total[k] += left[i];
        }
      }
      if (candidateCount == 0) return null;
      candidates[k] = java.util.Arrays.copyOf(candidateSlots, candidateCount);
      // renamed stacks last (the player named them for a reason); when dyeing, plain items before colored ones
      Integer[] boxed = new Integer[candidateCount];
      for (int j = 0; j < candidateCount; j++) boxed[j] = candidates[k][j];
      final boolean dyed = entry.dyed;
      java.util.Arrays.sort(boxed, (slotA, slotB) -> { // stable: keeps the slot order otherwise
        int comparison = Boolean.compare(named(inventory[slotA]), named(inventory[slotB]));
        return comparison != 0 || !dyed ? comparison : Boolean.compare(inventory[slotA].getMetadata() != 0, inventory[slotB].getMetadata() != 0);
      });
      for (int j = 0; j < candidateCount; j++) candidates[k][j] = boxed[j];
    }
    java.util.Arrays.sort(order, (posA, posB) -> Integer.compare(total[posA], total[posB]));
    int[] sources = new int[symbolOccurrences.length];
    int[] budget = {20000}; // never spend long on an odd recipe
    return assign(order, 0, candidates, left, sources, budget) ? sources : null;
  }

  /** Ingredient search order: the main inventory (slots 9-35) before the hotbar (0-8), so the
   *  items the player keeps at hand are used last. */
  static int slotOrder(int offset, int length) {
    if (length != 36) return offset;
    return offset < 27 ? offset + 9 : offset - 27;
  }

  private static boolean named(ItemStack stack) {
    try { return stack != null && stack.hasCustomName(); } catch (Throwable ignored) { return false; }
  }

  private static boolean assign(Integer[] order, int pos, int[][] cand, int[] left, int[] sources, int[] budget) {
    if (pos == order.length) return true;
    if (--budget[0] < 0) return false;
    int k = order[pos];
    for (int i : cand[k]) {
      if (left[i] <= 0) continue;
      left[i]--;
      sources[k] = i;
      if (assign(order, pos + 1, cand, left, sources, budget)) return true;
      left[i]++;
    }
    return false;
  }

  /** True if two symbols accept the same items (symbols are often separate objects for the same thing). */
  public static boolean sameItems(RecipeSymbol a, RecipeSymbol b) {
    if (a == b) return true;
    if (a == null || b == null) return false;
    try { if (a.equals(b)) return true; } catch (Throwable ignored) { }
    List<ItemStack> resolvedA = resolve(a), resolvedB = resolve(b);
    if (resolvedA.size() != resolvedB.size() || resolvedA.isEmpty()) return false;
    java.util.Set<Long> keysA = new java.util.HashSet<>();
    for (ItemStack itemA : resolvedA) if (itemA != null) keysA.add(outKey(itemA));
    for (ItemStack itemB : resolvedB) if (itemB == null || !keysA.contains(outKey(itemB))) return false;
    return true;
  }

  /** How many items the inventory has that the symbol accepts. */
  public static int count(RecipeSymbol symbol, ItemStack[] inventory) {
    int total = 0;
    for (ItemStack stack : inventory) if (stack != null && matches(symbol, stack)) total += stack.stackSize;
    return total;
  }
}
