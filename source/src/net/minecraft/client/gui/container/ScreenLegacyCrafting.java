package net.minecraft.client.gui.container;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.input.InputType;
import net.minecraft.client.input.controller.Button;
import net.minecraft.client.input.controller.ControllerInput;
import net.minecraft.client.option.GameSettings;
import net.minecraft.client.render.renderer.GLRenderer;
import net.minecraft.core.data.registry.recipe.RecipeSymbol;
import net.minecraft.core.item.ItemStack;
import net.minecraft.core.sound.SoundCategory;

/**
 * Console-style crafting screen (Legacy Console Edition), for the crafting table (3x3) and the
 * personal crafting (2x2, X in game). Layout: the console's SD crafting screen (positions taken from
 * the open-source Legacy4J mod's 480p layout) scaled by 1.25; everything drawn with rectangles
 * (no copied code or textures).
 *  - 7 tabs with icons across the top; a row of 12 slots, each a group of recipes; the selected one
 *    opens a column with the previous / next variant (ordered by material);
 *  - two recessed panels: item name, ingredient grid (missing ones in red), arrow and big result on the
 *    left; "Inventory" with the whole inventory on the right;
 *  - control guide with the controller's own button icons at the bottom left of the screen.
 * Same open menu as the classic screen; crafting is done by LegacyCrafting with normal clicks.
 */
public class ScreenLegacyCrafting extends ScreenContainerAbstract {
  // key codes read like BTA reads them (from whatever org.lwjgl.input.Keyboard the launcher provides)
  private static final int K_ESC = key("KEY_ESCAPE", 1), K_BACK = key("KEY_BACK", 14), K_TAB = key("KEY_TAB", 15),
      K_ENTER = key("KEY_RETURN", 28), K_UP = key("KEY_UP", 200), K_LEFT = key("KEY_LEFT", 203),
      K_RIGHT = key("KEY_RIGHT", 205), K_DOWN = key("KEY_DOWN", 208);

  private static int key(String name, int fallback) {
    try { return Class.forName("org.lwjgl.input.Keyboard").getField(name).getInt(null); } catch (Throwable ignored) { return fallback; }
  }

  // layout (GUI units): the console's SD crafting layout scaled by 1.25 (panel 316 x 138 + tabs),
  // shrunk as a whole when the screen is too small, always leaving room for the control guide
  static final int GUIDE_SPACE = 30, TOP_SPACE = 8;
  static final int TAB_UP = 24, BUTTON = 24, CELL = 20, RESULT = 31, INVENTORY_CELL = 14, BOTTOM_PANEL_H = 82;
  // per grid (3x3 table / 2x2 personal, like the console's two crafting screens)
  private final int PANEL_W, PANEL_H, ROW, ROW_X, ROW_Y, BOTTOM_PANEL_Y, GRID_X, LEFT_PANEL_W, INV_X, RIGHT_PANEL_W,
      GRID_OFFSET_X, GRID_OFFSET_Y, ARROW_X, ARROW_Y, ARROW_W, ARROW_H, RESULT_X, RESULT_Y;
  private static final int C_MISSING = 0x90FF3030, C_FADE = 0xA08B8B8B, C_FOCUS = 0xE0FFFFFF;

  private static int lastTab = 0;
  private static boolean lastOnlyCraftable = false;
  private static final Map<String, String> chosenVariant = new HashMap<>(); // group -> variant's item key

  /** One button: recipes that share a group, variants cycled with up/down. */
  private static final class Group {
    final String key;
    final List<LegacyRecipes.Entry> variants = new ArrayList<>();
    int pick;
    Group(String key) { this.key = key; }
    LegacyRecipes.Entry current() { return variants.get(Math.floorMod(pick, variants.size())); }
    LegacyRecipes.Entry at(int offset) { return variants.get(Math.floorMod(pick + offset, variants.size())); }
  }

  private final ScreenContainerAbstract original;
  private final int grid;
  private int tab = lastTab;
  private boolean onlyCraftable = lastOnlyCraftable;
  private final List<Group> groups = new ArrayList<>();
  private int selectedIndex = 0, rowStart = 0;
  private String selectedKey;
  private final Set<LegacyRecipes.Entry> craftable = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
  private final ItemStack[] tabIcons = new ItemStack[LegacyRecipes.TAB_COUNT];
  private long invSignature = Long.MIN_VALUE;
  private boolean listDirty = true;
  private String message;
  private long messageUntil;
  private boolean switching;
  private final long[] repeatAt = new long[9];  // left, right, up, down, craft, ingredient -, ingredient +, other ingredient up / down
  private int pending;                          // crafts still to do for "craft a stack"
  private LegacyRecipes.Entry pendingEntry;
  private long nextTidy, nextRefresh;
  private long frame, lastStepFrame = -1;
  private boolean untidy;
  private int[] visibleTabs = new int[0];      // tabs that have recipes for this grid (2x2: no Armour)
  /** Chosen item (id<<20|meta) per kind of ingredient (any planks, any wool, any log...), for every recipe that
   *  takes that kind, until the game closes (a choice the player no longer has is simply ignored). */
  private static final Map<String, Long> PIN_BY_KIND = new HashMap<>();
  private static int pinVersion; // changes with every choice (cached plans depend on it)
  private RecipeSymbol[] pendingPinSyms;
  private ItemStack[] pendingPins;
  private boolean pendingChosen; // "Create All" started with the chosen items: it stops when they run out

  public ScreenLegacyCrafting(ScreenContainerAbstract original) {
    super(original.inventorySlots);
    this.original = original;
    this.grid = original.inventorySlots instanceof net.minecraft.core.player.inventory.menu.MenuCrafting ? 3 : 2;
    if (grid == 3) { // crafting table
      PANEL_W = 316; ROW = 12; ROW_X = 14; ROW_Y = 22; BOTTOM_PANEL_Y = 86; GRID_X = 9; LEFT_PANEL_W = 146; INV_X = 161; RIGHT_PANEL_W = 146;
      GRID_OFFSET_X = 13; GRID_OFFSET_Y = 18; ARROW_X = 80; ARROW_Y = 40; ARROW_W = 20; ARROW_H = 17; RESULT_X = 108; RESULT_Y = 33;
    } else {         // personal 2x2: narrower (both leave room under the row for the variant column)
      PANEL_W = 259; ROW = 10; ROW_X = 9; ROW_Y = 22; BOTTOM_PANEL_Y = 86; GRID_X = 5; LEFT_PANEL_W = 110; INV_X = 118; RIGHT_PANEL_W = 136;
      GRID_OFFSET_X = 12; GRID_OFFSET_Y = 22; ARROW_X = 56; ARROW_Y = 34; ARROW_W = 16; ARROW_H = 15; RESULT_X = 76; RESULT_Y = 27;
    }
    PANEL_H = BOTTOM_PANEL_Y + BOTTOM_PANEL_H + 5;
    this.xSize = PANEL_W;
    this.ySize = PANEL_H;
  }

  @Override
  public void init() {
    super.init();
    if (mc.controllerInput != null) mc.controllerInput.hideCursor = true;
  }

  @Override
  public void removed() {
    if (mc.controllerInput != null) mc.controllerInput.hideCursor = false;
    remember();
    if (!switching) original.removed(); // closes the menu exactly like the classic screen would
  }

  private void remember() {
    lastTab = tab;
    lastOnlyCraftable = onlyCraftable;
  }

  private void toClassic() {
    switching = true;
    if (mc.controllerInput != null) mc.controllerInput.hideCursor = false;
    remember();
    LegacyCrafting.switchTo(original);
  }

  @Override
  protected void drawGuiContainerBackgroundLayer(float partialTick) { } // render() draws everything

  // ---------------------------------------------------------------- data

  private long frameSignature = Long.MIN_VALUE; // signature() of this frame (set by refresh())

  private long signature() {
    long hash = 1;
    for (int i = 0; i < 36; i++) {
      ItemStack stack = mc.thePlayer.inventory.getItem(i);
      hash = hash * 31 + (stack == null ? 0 : ((stack.itemID * 4099L + stack.getMetadata()) * 131 + stack.stackSize) * 7 + LegacyRecipes.contents(stack));
    }
    return hash;
  }

  private ItemStack[] inventory() {
    ItemStack[] stacks = new ItemStack[36];
    for (int i = 0; i < 36; i++) stacks[i] = mc.thePlayer.inventory.getItem(i);
    return stacks;
  }

  private void refresh() {
    long currentSignature = frameSignature = signature();
    if (!listDirty && currentSignature == invSignature) return;
    invSignature = currentSignature;
    listDirty = false;
    ItemStack[] stacks = inventory();
    craftable.clear();
    LinkedHashMap<String, Group> byKey = new LinkedHashMap<>();
    java.util.Arrays.fill(tabIcons, null);
    List<LegacyRecipes.Entry> inTab = new ArrayList<>();
    boolean[] hasRecipes = new boolean[LegacyRecipes.TAB_COUNT];
    for (LegacyRecipes.Entry entry : LegacyRecipes.all()) {
      if (!entry.fits(grid)) continue;
      hasRecipes[entry.category] = true;
      if (tabIcons[entry.category] == null || entry.key.equals(LegacyRecipes.TAB_ICONS[entry.category])) tabIcons[entry.category] = entry.output;
      if (entry.category != tab) continue;
      RecipeSymbol[] choosable = choices(entry);
      boolean canCraft = (choosable.length == 0 ? LegacyRecipes.plan(entry, stacks) : LegacyRecipes.plan(entry, stacks, choosable, pins(choosable, stacks))) != null;
      if (canCraft) craftable.add(entry);
      if (onlyCraftable && !canCraft) continue;
      inTab.add(entry);
    }
    int visibleCount = 0;
    for (boolean present : hasRecipes) if (present) visibleCount++;
    visibleTabs = new int[visibleCount];
    visibleCount = 0;
    for (int tabIndex = 0; tabIndex < hasRecipes.length; tabIndex++) if (hasRecipes[tabIndex]) visibleTabs[visibleCount++] = tabIndex;
    if (visibleTabs.length > 0 && !hasRecipes[tab]) { tab = visibleTabs[0]; listDirty = true; refresh(); return; }
    // buttons and variants arranged like the console, families and order taken from BTA's creative menu
    for (LegacyRecipes.Button button : LegacyRecipes.arrange(inTab)) {
      Group group = new Group(button.key);
      group.variants.addAll(button.variants);
      byKey.put(group.key, group);
    }
    Map<String, String> showing = new HashMap<>(); // what each button shows now: a refresh must not change it
    for (Group group : groups) showing.put(group.key, group.current().key);
    groups.clear();
    for (Group group : byKey.values()) {
      String chosen = chosenVariant.get(group.key);
      if (chosen == null) chosen = showing.get(group.key);
      int pickIndex = -1;
      if (chosen != null) for (int i = 0; i < group.variants.size() && pickIndex < 0; i++) if (chosen.equals(group.variants.get(i).key)) pickIndex = i;
      if (pickIndex < 0) for (int i = 0; i < group.variants.size() && pickIndex < 0; i++) if (craftable.contains(group.variants.get(i))) pickIndex = i;
      group.pick = Math.max(0, pickIndex);
      groups.add(group);
    }
    int keptIndex = -1;
    for (int i = 0; i < groups.size(); i++) if (groups.get(i).key.equals(selectedKey)) keptIndex = i;
    select(keptIndex >= 0 ? keptIndex : Math.min(selectedIndex, Math.max(0, groups.size() - 1)));
  }

  private Group selectedGroup() { return groups.isEmpty() ? null : groups.get(selectedIndex); }

  private LegacyRecipes.Entry selected() { Group group = selectedGroup(); return group == null ? null : group.current(); }

  private void sound(String name) {
    try { mc.sndManager.playSound(name, SoundCategory.GUI_SOUNDS, 1.0f, 1.0f); } catch (Throwable ignored) { }
  }

  /** Moves along the row (with the console's focus sound when the selection changes). */
  private void move(int index) {
    int before = selectedIndex;
    select(index);
    if (selectedIndex != before) sound("ui.ui_focus");
  }

  private void select(int index) {
    if (groups.isEmpty()) { selectedIndex = 0; rowStart = 0; return; }
    selectedIndex = Math.max(0, Math.min(groups.size() - 1, index));
    selectedKey = groups.get(selectedIndex).key;
    if (selectedIndex < rowStart) rowStart = selectedIndex;
    if (selectedIndex >= rowStart + ROW) rowStart = selectedIndex - ROW + 1;
  }

  private void variant(int direction) {
    Group group = selectedGroup();
    if (group == null || group.variants.size() < 2) return;
    group.pick = Math.floorMod(group.pick + direction, group.variants.size());
    chosenVariant.put(group.key, group.current().key);
    sound("ui.ui_focus");
  }

  private void tabBy(int direction) {
    if (visibleTabs.length == 0) return;
    int currentPosition = 0;
    for (int i = 0; i < visibleTabs.length; i++) if (visibleTabs[i] == tab) currentPosition = i;
    setTab(visibleTabs[Math.floorMod(currentPosition + direction, visibleTabs.length)]);
  }

  private void setTab(int newTab) {
    if (newTab != tab) sound("ui.ui_scroll");
    tab = Math.floorMod(newTab, LegacyRecipes.TAB_COUNT);
    selectedIndex = 0;
    rowStart = 0;
    selectedKey = null;
    listDirty = true;
  }

  private void say(String text) {
    message = text;
    messageUntil = System.currentTimeMillis() + 2000;
  }

  // ---------------------------------------------------------------- crafting

  /** Queues crafts of the selected recipe; step() does them (so never more than allowed per frame). */
  private void craft(int times) {
    LegacyRecipes.Entry entry = selected();
    if (entry == null || times <= 0) return;
    pendingEntry = entry;
    pending = times;
    View recipeView = view();
    pendingPinSyms = recipeView == null ? null : recipeView.syms;
    pendingPins = recipeView == null ? null : recipeView.pins;
    pendingChosen = times > 1 && pendingPins != null && LegacyRecipes.planChosen(entry, inventory(), pendingPinSyms, pendingPins) != null;
    madeSound = false;
  }

  private boolean madeSound, craftFailedThisPress;

  private int stackCrafts() {
    LegacyRecipes.Entry entry = selected();
    if (entry == null) return 0;
    return Math.max(1, entry.output.getMaxStackSize() / Math.max(1, entry.output.stackSize));
  }

  /**
   * Once per frame: does queued crafts (in multiplayer, where the server checks every click, at most
   * one per frame and within LegacyCrafting's packet budget; up to 8 in single player) and keeps
   * grid/cursor tidy.
   */
  private void step() {
    if (frame == lastStepFrame) return;
    lastStepFrame = frame;
    if (pending > 0 && pendingEntry != null) {
      int perFrame = LegacyCrafting.multiplayer() ? 1 : 8;
      for (int i = 0; i < perFrame && pending > 0 && LegacyCrafting.mayCraftNow(pendingEntry); i++) { // multiplayer: packet budget
        pending--;
        if (pendingChosen && LegacyRecipes.planChosen(pendingEntry, inventory(), pendingPinSyms, pendingPins) == null) {
          pending = 0; listDirty = true; break; // the chosen items ran out: never go on with other ones
        }
        String failure = LegacyCrafting.craftOnce(inventorySlots, pendingEntry, pendingPinSyms, pendingPins);
        if (failure != null) { say(failure); sound("ui.crafting_fail"); craftFailedThisPress = true; pending = 0; listDirty = true; break; }
        if (!madeSound) { sound("ui.crafting_made"); madeSound = true; } // once per press, not per item of a stack
      }
      long nowMillis = System.currentTimeMillis();
      if (pending == 0 || nowMillis >= nextRefresh) { listDirty = true; nextRefresh = nowMillis + 250; } // not every frame on a phone
    }
    long now = System.currentTimeMillis();
    if (now >= nextTidy) {
      nextTidy = now + 500;
      untidy = !LegacyCrafting.tidy(inventorySlots);
    }
  }

  // ---------------------------------------------------------------- ingredient choice (right stick)

  /** The recipe's ingredients that can be one of several items (any planks, any wool, any log, any bucket...), one
   *  per kind, in grid order. Cached per recipe (render thread only). */
  private static final Map<LegacyRecipes.Entry, RecipeSymbol[]> CHOICES = new IdentityHashMap<>();
  private static Object choicesFor; // the recipe list CHOICES and KIND were filled from
  private static RecipeSymbol[] choices(LegacyRecipes.Entry entry) {
    if (entry == null) return new RecipeSymbol[0];
    Object recipeList = LegacyRecipes.built();
    if (recipeList != choicesFor) { CHOICES.clear(); KIND.clear(); choicesFor = recipeList; }
    RecipeSymbol[] cached = CHOICES.get(entry);
    if (cached == null) {
      List<RecipeSymbol> result = new ArrayList<>();
      java.util.Set<String> kinds = new java.util.HashSet<>();
      for (RecipeSymbol symbol : entry.occurrences())
        if (LegacyRecipes.resolve(symbol).size() > 1 && !kind(symbol).isEmpty() && kinds.add(kind(symbol))) result.add(symbol);
      cached = result.toArray(new RecipeSymbol[0]);
      if (CHOICES.size() > 4096) CHOICES.clear(); // recipe list rebuilt: entries are new objects
      CHOICES.put(entry, cached);
    }
    return cached;
  }

  /** Identity of an item kind (id and metadata). */
  private static long itemKey(ItemStack stack) { return ((long) stack.itemID << 20) | (stack.getMetadata() & 0xFFFFF); }

  /** Identity of a choice: as itemKey, but only the item for tools (their metadata is their wear). */
  private static long choiceKey(ItemStack stack) {
    boolean damageable = false;
    try { damageable = stack.isItemStackDamageable(); } catch (Throwable ignored) { }
    return (damageable ? ((long) stack.itemID << 20) | 0xFFFFF : itemKey(stack)) * 31 + LegacyRecipes.contents(stack);
  }

  /** Kind of a choosable ingredient: the set of items it accepts (same kind = same choice in every recipe).
   *  Cached per symbol (render thread only). */
  private static final Map<RecipeSymbol, String> KIND = new IdentityHashMap<>();
  private static String kind(RecipeSymbol sym) {
    String cachedKind = KIND.get(sym);
    if (cachedKind == null) {
      List<ItemStack> options = LegacyRecipes.resolve(sym);
      long[] keys = new long[options.size()];
      int count = 0;
      for (ItemStack stack : options) if (stack != null) keys[count++] = itemKey(stack);
      java.util.Arrays.sort(keys, 0, count);
      StringBuilder builder = new StringBuilder();
      for (int i = 0; i < count; i++) builder.append(keys[i]).append(',');
      cachedKind = builder.toString();
      if (KIND.size() > 4096) KIND.clear(); // recipe list rebuilt (other server): symbols are new objects
      KIND.put(sym, cachedKind);
    }
    return cachedKind;
  }

  /** One of an item, never the inventory's own stack (drawing it must not show its name, damage or count). */
  private static ItemStack singleOf(ItemStack stack) {
    ItemStack copy = stack.copy();
    copy.stackSize = 1;
    boolean damageable = false;
    try { damageable = copy.isItemStackDamageable(); } catch (Throwable ignored) { }
    if (copy.getMetadata() == -1 || damageable) copy.setMetadata(0);
    return copy;
  }

  /** The options of a choosable ingredient the player has (one of each, inventory order), cached per symbol until
   *  the inventory changes. */
  private final Map<RecipeSymbol, List<ItemStack>> ownedChoices = new IdentityHashMap<>();
  private long ownedChoicesSignature = Long.MIN_VALUE;

  private List<ItemStack> owned(RecipeSymbol sym, ItemStack[] stacks) {
    if (frameSignature != ownedChoicesSignature) { ownedChoices.clear(); ownedChoicesSignature = frameSignature; }
    List<ItemStack> have = ownedChoices.get(sym);
    if (have == null) {
      have = new ArrayList<>();
      java.util.Set<Long> seen = new java.util.HashSet<>();
      for (int slotIndex = 0; slotIndex < stacks.length; slotIndex++) {
        ItemStack stack = stacks[LegacyRecipes.slotOrder(slotIndex, stacks.length)];
        if (stack != null && LegacyRecipes.matches(sym, stack) && seen.add(choiceKey(stack))) have.add(singleOf(stack));
      }
      ownedChoices.put(sym, have);
    }
    return have;
  }

  /** The chosen item for each of those ingredients while the player has it, else null = any. */
  private ItemStack[] pins(RecipeSymbol[] syms, ItemStack[] stacks) {
    ItemStack[] result = null;
    for (int j = 0; j < syms.length; j++) {
      Long chosenKey = PIN_BY_KIND.get(kind(syms[j]));
      if (chosenKey == null) continue;
      for (ItemStack option : owned(syms[j], stacks))
        if (choiceKey(option) == chosenKey) { if (result == null) result = new ItemStack[syms.length]; result[j] = option; break; }
    }
    return result;
  }

  /** What the selected recipe shows and would use: recomputed only when the recipe, the inventory or a choice
   *  change (not every frame). */
  private static final class View {
    LegacyRecipes.Entry entry;
    long signature;
    int pinVersion;
    RecipeSymbol[] syms;   // choosable ingredients
    ItemStack[] pins;      // chosen item per choosable ingredient (null array or element: any)
    int[] changeable;      // indices of syms the player has two or more options for
    int[] sourceSlots;     // inventory slot per occurrence (null: can't make it)
    int[] choiceOf;        // per occurrence: index in syms, -1 if fixed
    boolean[] missing;     // per occurrence: not enough of it (red), when the recipe can't be made
    ItemStack[] used;      // per occurrence: the item the craft takes (one of it), when it can be made
  }
  private final View view = new View();
  private int focusSym; // which choosable ingredient the right stick changes (index in view.syms)

  /** Position of the focused ingredient in view.changeable (the first one if it is no longer changeable). */
  private static int focusAt(View recipeView, int sym) {
    for (int i = 0; i < recipeView.changeable.length; i++) if (recipeView.changeable[i] == sym) return i;
    return 0;
  }

  private View view() {
    LegacyRecipes.Entry entry = selected();
    if (entry == null) return null;
    View recipeView = view;
    if (recipeView.entry != entry || recipeView.signature != frameSignature || recipeView.pinVersion != pinVersion) {
      if (recipeView.entry != entry) focusSym = -1;
      ItemStack[] stacks = inventory();
      recipeView.entry = entry; recipeView.signature = frameSignature; recipeView.pinVersion = pinVersion;
      recipeView.syms = choices(entry);
      recipeView.pins = pins(recipeView.syms, stacks);
      int changeableCount = 0;
      int[] changeableIndices = new int[recipeView.syms.length];
      for (int j = 0; j < recipeView.syms.length; j++) if (owned(recipeView.syms[j], stacks).size() > 1) changeableIndices[changeableCount++] = j;
      recipeView.changeable = java.util.Arrays.copyOf(changeableIndices, changeableCount);
      RecipeSymbol[] occurrences = entry.occurrences();
      recipeView.choiceOf = new int[occurrences.length];
      for (int k = 0; k < occurrences.length; k++) {
        recipeView.choiceOf[k] = -1;
        for (int j = 0; j < recipeView.syms.length && recipeView.choiceOf[k] < 0; j++) if (LegacyRecipes.sameItems(occurrences[k], recipeView.syms[j])) recipeView.choiceOf[k] = j;
      }
      recipeView.sourceSlots = recipeView.syms.length == 0 ? LegacyRecipes.plan(entry, stacks) : LegacyRecipes.plan(entry, stacks, recipeView.syms, recipeView.pins);
      recipeView.used = new ItemStack[occurrences.length];
      recipeView.missing = new boolean[occurrences.length];
      for (int k = 0; k < occurrences.length; k++) {
        if (recipeView.sourceSlots != null) {
          if (k < recipeView.sourceSlots.length && recipeView.sourceSlots[k] >= 0 && recipeView.sourceSlots[k] < stacks.length && stacks[recipeView.sourceSlots[k]] != null) recipeView.used[k] = singleOf(stacks[recipeView.sourceSlots[k]]);
          continue;
        }
        int needed = 0;
        for (RecipeSymbol sym : occurrences) if (LegacyRecipes.sameItems(sym, occurrences[k])) needed++;
        int have;
        have = LegacyRecipes.count(occurrences[k], stacks); // (a choice is only a preference: any accepted item counts)
        recipeView.missing[k] = have < needed;
      }
    }
    return recipeView;
  }

  /** Next / previous option of the focused ingredient (starting from the one the grid shows). */
  private void changeIngredient(int direction) {
    View recipeView = view();
    if (recipeView == null || recipeView.changeable.length == 0) return;
    int symIndex = recipeView.changeable[focusAt(recipeView, focusSym)];
    focusSym = symIndex;
    ItemStack[] stacks = inventory();
    List<ItemStack> options = owned(recipeView.syms[symIndex], stacks);
    if (options.size() < 2) return;
    ItemStack current = recipeView.pins == null ? null : recipeView.pins[symIndex];
    if (current == null && recipeView.sourceSlots != null) // nothing chosen: the item the craft would use now
      for (int k = 0; k < recipeView.choiceOf.length && current == null; k++)
        if (recipeView.choiceOf[k] == symIndex && recipeView.sourceSlots[k] >= 0 && recipeView.sourceSlots[k] < stacks.length) current = stacks[recipeView.sourceSlots[k]];
    int currentIndex = -1;
    if (current != null) for (int i = 0; i < options.size(); i++) if (choiceKey(options.get(i)) == choiceKey(current)) currentIndex = i;
    int nextIndex = currentIndex < 0 ? (direction > 0 ? 0 : options.size() - 1) : Math.floorMod(currentIndex + direction, options.size());
    PIN_BY_KIND.put(kind(recipeView.syms[symIndex]), choiceKey(options.get(nextIndex)));
    pinVersion++;
    listDirty = true; // "can make it" of the row depends on the choice
    sound("ui.ui_focus");
  }

  /** The right stick changes another of the recipe's choosable ingredients (when it has several). */
  private void otherIngredient(int direction) {
    View recipeView = view();
    if (recipeView == null || recipeView.changeable.length < 2) return;
    focusSym = recipeView.changeable[Math.floorMod(focusAt(recipeView, focusSym) + direction, recipeView.changeable.length)];
    sound("ui.ui_focus");
  }

  // ---------------------------------------------------------------- input

  /** true on press and then repeatedly while held. */
  private boolean repeat(int id, boolean down, boolean pressedNow, int firstMs, int everyMs) {
    long now = System.currentTimeMillis();
    if (!down) { repeatAt[id] = 0; return false; }
    if (pressedNow || repeatAt[id] == 0) { repeatAt[id] = now + firstMs; return true; }
    if (now >= repeatAt[id]) { repeatAt[id] = now + everyMs; return true; }
    return false;
  }

  @Override
  public void controllerInput(ControllerInput input) {
    input.hideCursor = true;
    step();
    refresh();
    float leftStickX = input.joyLeft.getX(), leftStickY = input.joyLeft.getY();
    boolean left = input.digitalPad.left.isPressed() || leftStickX < -0.5f, right = input.digitalPad.right.isPressed() || leftStickX > 0.5f;
    boolean up = input.digitalPad.up.isPressed() || leftStickY < -0.5f, down = input.digitalPad.down.isPressed() || leftStickY > 0.5f;
    if (repeat(0, left, input.digitalPad.left.pressedThisFrame(), 300, 80)) move(selectedIndex - 1);
    if (repeat(1, right, input.digitalPad.right.pressedThisFrame(), 300, 80)) move(selectedIndex + 1);
    if (repeat(2, up, input.digitalPad.up.pressedThisFrame(), 350, 150)) variant(-1);
    if (repeat(3, down, input.digitalPad.down.pressedThisFrame(), 350, 150)) variant(1);
    float rightStickX = input.joyRight.getX(), rightStickY = input.joyRight.getY();
    boolean horizontal = Math.abs(rightStickX) >= Math.abs(rightStickY); // only the main direction of a diagonal counts
    if (repeat(5, horizontal && rightStickX < -0.5f, false, 350, 200)) changeIngredient(-1);
    if (repeat(6, horizontal && rightStickX > 0.5f, false, 350, 200)) changeIngredient(1);
    if (repeat(7, !horizontal && rightStickY < -0.5f, false, 350, 200)) otherIngredient(-1);
    if (repeat(8, !horizontal && rightStickY > 0.5f, false, 350, 200)) otherIngredient(1);
    if (input.joyRight.button.pressedThisFrame()) changeIngredient(1);
    if (input.buttonLeftShoulder.pressedThisFrame() || input.buttonLeftTrigger.pressedThisFrame()) tabBy(-1);
    if (input.buttonRightShoulder.pressedThisFrame() || input.buttonRightTrigger.pressedThisFrame()) tabBy(1);
    if (!input.buttonA.isPressed()) craftFailedThisPress = false;
    if (repeat(4, input.buttonA.isPressed(), input.buttonA.pressedThisFrame(), 450, 140) && (input.buttonA.pressedThisFrame() || !craftFailedThisPress)) craft(1);
    if (input.buttonX.pressedThisFrame() || input.joyLeft.button.pressedThisFrame()) craft(stackCrafts());
    if (input.buttonY.pressedThisFrame()) { onlyCraftable = !onlyCraftable; listDirty = true; sound("ui.ui_click"); }
    if (input.buttonB.pressedThisFrame() || input.buttonStart.pressedThisFrame()) { input.cancelButtonPresses(); sound("ui.ui_back"); mc.thePlayer.closeScreen(); }
  }

  @Override
  public void keyPressed(char character, int keyCode, int mouseX, int mouseY) {
    if (keyCode == K_ESC || keyCode == K_BACK || GameSettings.KEY_INVENTORY.isKeyOrMouse(keyCode, -1)) { mc.thePlayer.closeScreen(); return; }
    refresh();
    if (keyCode == K_LEFT) move(selectedIndex - 1);
    else if (keyCode == K_RIGHT) move(selectedIndex + 1);
    else if (keyCode == K_UP) variant(-1);
    else if (keyCode == K_DOWN) variant(1);
    else if (keyCode == K_ENTER) craft(1);
    else if (keyCode == K_TAB) tabBy(1);
  }

  private float scale = 1f;

  private int guideSpace = GUIDE_SPACE; // room kept under the panel for the control guide

  /** The control guide entries (also used to reserve room for it). */
  private List<Object[]> guideEntries() {
    ControllerInput input = mc.controllerInput;
    List<Object[]> guide = new ArrayList<>();
    if (input == null) return guide;
    guide.add(new Object[]{input.buttonA, "Create"});
    guide.add(new Object[]{input.buttonB, "Exit"});
    guide.add(new Object[]{new Button[]{input.buttonLeftShoulder, input.buttonRightShoulder}, "Change Group"});
    View recipeView = view();
    if (recipeView != null && recipeView.changeable.length > 0) guide.add(new Object[]{input.joyRight.button, recipeView.changeable.length > 1 ? "Change Ingredients" : "Change Ingredient"});
    guide.add(new Object[]{input.buttonY, onlyCraftable ? "Show All" : "Show Craftable"});
    guide.add(new Object[]{input.buttonX, "Create All"});
    return guide;
  }

  /** Fits panel + tabs between the top margin and the guide; the drawing is scaled when it doesn't. */
  private void fit() {
    guideSpace = GUIDE_SPACE;
    if (ConsoleUI.controller()) guideSpace += 17 * (ConsoleUI.guideLines(fontRenderer, width - 8, guideEntries()) - 1);
    float fitHeight = (height - guideSpace - TOP_SPACE) / (float) (PANEL_H + TAB_UP);
    float fitWidth = (width - 8) / (float) PANEL_W;
    scale = Math.max(0.5f, Math.min(1f, Math.min(fitHeight, fitWidth)));
  }
  private int scaledWidth() { return (int) (width / scale); }
  private int left() { return Math.max(2, (scaledWidth() - PANEL_W) / 2); }
  private int top() {
    float areaTop = TOP_SPACE / scale, areaBottom = (height - guideSpace) / scale;
    return (int) (areaTop + Math.max(0, (areaBottom - areaTop - (PANEL_H + TAB_UP)) / 2)) + TAB_UP;
  }
  private int rowX() { return left() + ROW_X; }
  private int rowY() { return top() + ROW_Y; }
  private int gridX() { return left() + GRID_X + GRID_OFFSET_X; }
  private int gridY() { return top() + BOTTOM_PANEL_Y + GRID_OFFSET_Y; }
  private int resultX() { return left() + GRID_X + RESULT_X; }
  private int resultY() { return top() + BOTTOM_PANEL_Y + RESULT_Y; }
  private int tabStep() { return (PANEL_W - 1) / Math.max(1, visibleTabs.length); }

  @Override
  public void mouseClicked(int mouseX, int mouseY, int button) {
    if (button != 0) return;
    refresh();
    fit();
    mouseX = (int) (mouseX / scale);
    mouseY = (int) (mouseY / scale);
    int panelLeft = left(), panelTop = top();
    int rowLeft = rowX(), rowTop = rowY();
    // the open variant column is drawn over the tabs and the panels, so it gets the tap first
    Group group = selectedGroup();
    if (group != null && group.variants.size() > 1 && selectedIndex >= rowStart && selectedIndex < rowStart + ROW) {
      int buttonX = rowLeft + (selectedIndex - rowStart) * BUTTON;
      if (mouseX >= buttonX - 4 && mouseX < buttonX + BUTTON + 4) {
        if (group.variants.size() > 2 && mouseY >= rowTop - BUTTON - 10 && mouseY < rowTop) { variant(-1); return; }
        if (mouseY >= rowTop + BUTTON && mouseY < rowTop + 2 * BUTTON + 10) { variant(1); return; }
      }
    }
    if (mouseY >= panelTop - TAB_UP && mouseY < panelTop && mouseX >= panelLeft && mouseX < panelLeft + tabStep() * visibleTabs.length) {
      setTab(visibleTabs[Math.min(visibleTabs.length - 1, (mouseX - panelLeft) / tabStep())]);
      return;
    }
    if (mouseX >= rowLeft && mouseX < rowLeft + ROW * BUTTON && mouseY >= rowTop && mouseY < rowTop + BUTTON) {
      int i = rowStart + (mouseX - rowLeft) / BUTTON;
      if (i < groups.size()) { if (i == selectedIndex) craft(1); else move(i); return; }
    }
    // tap an ingredient that can be one of several items: choose the next one
    int gridLeft = gridX(), gridTop = gridY();
    if (mouseX >= gridLeft && mouseX < gridLeft + grid * CELL && mouseY >= gridTop && mouseY < gridTop + grid * CELL) {
      View recipeView = view();
      LegacyRecipes.Entry entry = selected();
      if (recipeView == null || entry == null) return;
      int cell = (mouseX - gridLeft) / CELL + (mouseY - gridTop) / CELL * grid;
      int[] cells = entry.cells(grid);
      for (int k = 0; k < cells.length; k++) {
        if (cells[k] != cell || recipeView.choiceOf[k] < 0) continue;
        for (int i = 0; i < recipeView.changeable.length; i++) if (recipeView.changeable[i] == recipeView.choiceOf[k]) { focusSym = recipeView.choiceOf[k]; changeIngredient(1); }
      }
      return;
    }
    int resultLeft = resultX(), resultTop = resultY();
    if (mouseX >= resultLeft && mouseX < resultLeft + RESULT && mouseY >= resultTop && mouseY < resultTop + RESULT) craft(1);
  }

  @Override
  public void mouseReleased(int mouseX, int mouseY, int button) { }

  @Override
  public void guiSpecificControllerInput(ControllerInput input) { } // everything is handled in controllerInput()

  /** A translucent rectangle over item icons (they write depth), so it is drawn in front of them. */
  private void overlay(int x0, int y0, int x1, int y1, int argb) {
    GLRenderer.pushFrame();
    try {
      GLRenderer.modelM4f().translate(0f, 0f, 300f);
      drawRect(x0, y0, x1, y1, argb);
    } finally {
      GLRenderer.popFrame();
    }
  }

  // ---------------------------------------------------------------- drawing

  /** An item icon without its stack count (recipe slots, tabs, ingredients). */
  private void item(ItemStack stack, int x, int y) {
    if (stack == null) return;
    if (stack.stackSize != 1) { stack = stack.copy(); stack.stackSize = 1; }
    GLRenderer.setColor4f(1, 1, 1, 1);
    itemElement.render(stack, x, y);
  }

  /** An item icon with its count, like a slot (the result). */
  private void itemWithCount(ItemStack stack, int x, int y) {
    if (stack == null) return;
    GLRenderer.setColor4f(1, 1, 1, 1);
    itemElement.render(stack, x, y);
  }

  /** A scale near the wanted one that keeps whole screen pixels per texture pixel (crisp, like on console). */
  private float crisp(float wanted) {
    int guiScale;
    try { guiScale = Math.max(1, mc.resolution.getScale()); } catch (Throwable ignored) { guiScale = 3; }
    return Math.max(1, Math.round(guiScale * wanted)) / (float) guiScale;
  }

  /** Small inventory icon (about 11 units in a 14-unit slot) with a small count in the corner. */
  private void miniItem(ItemStack stack, int x, int y) {
    if (stack == null) return;
    int count = stack.stackSize;
    if (count != 1) { stack = stack.copy(); stack.stackSize = 1; }
    float iconScale = crisp(0.72f), offset = (INVENTORY_CELL - 16 * iconScale) / 2f;
    GLRenderer.setColor4f(1, 1, 1, 1);
    GLRenderer.pushFrame();
    try {
      GLRenderer.modelM4f().translate(x + offset, y + offset, 0f).scale(iconScale, iconScale, 1f);
      itemElement.render(stack, 0, 0);
    } finally {
      GLRenderer.popFrame();
    }
    if (count > 1) {
      String countText = String.valueOf(count);
      float countScale = crisp(0.6f);
      GLRenderer.pushFrame();
      try {
        GLRenderer.modelM4f().translate(x + INVENTORY_CELL - fontRenderer.stringWidth(countText) * countScale, y + INVENTORY_CELL - 8 * countScale, 300f).scale(countScale, countScale, 1f);
        drawStringShadow(fontRenderer, countText, 0, 0, 0xFFFFFFFF);
      } finally {
        GLRenderer.popFrame();
      }
    }
  }

  /** A group ingredient (any planks, any wool, any log...) cycles once a second through the options the player has,
   *  or through all of them when the player has none. */
  private ItemStack shown(RecipeSymbol sym, ItemStack[] stacks) {
    List<ItemStack> options = LegacyRecipes.resolve(sym);
    if (options.size() > 1) {
      List<ItemStack> have = owned(sym, stacks);
      if (!have.isEmpty()) options = have;
    }
    ItemStack stack = options.isEmpty() ? sym.getStack() : options.get((int) ((System.currentTimeMillis() / 1000) % options.size()));
    if (stack == null) return null;
    if (stack.getMetadata() == -1 || stack.stackSize != 1) { stack = stack.copy(); if (stack.getMetadata() == -1) stack.setMetadata(0); stack.stackSize = 1; }
    return stack;
  }

  /** A recipe slot of the row (or of the variant column): the item, translucent when it can't be made. */
  private void recipeButton(int x, int y, LegacyRecipes.Entry entry) { recipeButton(x, y, entry, entry != null && craftable.contains(entry)); }

  private void recipeButton(int x, int y, LegacyRecipes.Entry entry, boolean canCraft) {
    ConsoleUI.slot(this, x, y, BUTTON);
    if (entry == null) return;
    item(entry.output, x + (BUTTON - 16) / 2, y + (BUTTON - 16) / 2);
    if (!canCraft) overlay(x + 1, y + 1, x + BUTTON - 1, y + BUTTON - 1, C_FADE);
  }

  private boolean selectedCan; // the selected recipe with the chosen ingredient (set while drawing the grid)

  /** Tabs like the console's: the inactive ones darker and lower, the active one open into the panel. */
  private void tab(int visibleIndex, int x, int y, boolean selected) {
    String[] shape = !selected ? ConsoleUI.TAB_LOW
        : visibleIndex == 0 ? ConsoleUI.TAB_LEFT : visibleIndex == visibleTabs.length - 1 ? ConsoleUI.TAB_RIGHT : ConsoleUI.TAB_MIDDLE;
    int tabWidth = tabStep() + 1;
    ConsoleUI.drawNinePatch(this, x, y, x + tabWidth, y + TAB_UP + 6, shape);
    item(tabIcons[visibleTabs[visibleIndex]], x + (tabWidth - 16) / 2, y + (selected ? 6 : 4)); // inactive: clear of the panel border
  }

  private String trim(String text, int maxWidth) {
    if (fontRenderer.stringWidth(text) <= maxWidth) return text;
    while (text.length() > 1 && fontRenderer.stringWidth(text + "..") > maxWidth) text = text.substring(0, text.length() - 1);
    return text + "..";
  }

  @Override
  public void render(int mouseX, int mouseY, float partialTick) {
    if (mc.inputType == InputType.CONTROLLER && mc.controllerInput == null) mc.setInputType(InputType.KEYBOARD); // pad unplugged: touch works again
    step(); // no-op if controllerInput() already did it this frame
    refresh();
    renderBackground();
    fit();
    GLRenderer.pushFrame();
    try {
      drawPanel();
    } finally {
      GLRenderer.popFrame();
    }

    // control guide (console style, bottom left of the screen, never scaled)
    if (ConsoleUI.controller()) ConsoleUI.guide(this, mc, fontRenderer, width - 8, height - 22, guideEntries());
    GLRenderer.setColor4f(1, 1, 1, 1);
    frame++; // input (controllerInput) and drawing of one frame share one step()
  }

  /** Everything inside the (possibly scaled) frame: tabs, panel, row, grid, inventory, variant column. */
  private void drawPanel() {
    if (scale < 1f) GLRenderer.modelM4f().scale(scale, scale, 1f);
    int panelLeft = left(), panelTop = top();

    // tabs across the top: the others behind (a little lower), the selected one joined to the panel
    int selectedVisibleIndex = 0;
    for (int visibleIndex = 0; visibleIndex < visibleTabs.length; visibleIndex++) {
      if (visibleTabs[visibleIndex] == tab) { selectedVisibleIndex = visibleIndex; continue; }
      tab(visibleIndex, panelLeft + visibleIndex * tabStep(), panelTop - TAB_UP + 3, false);
    }
    ConsoleUI.panel(this, panelLeft, panelTop, panelLeft + PANEL_W, panelTop + PANEL_H);
    if (visibleTabs.length > 0) tab(selectedVisibleIndex, panelLeft + selectedVisibleIndex * tabStep(), panelTop - TAB_UP, true);
    if (message != null && System.currentTimeMillis() < messageUntil) // failures replace the title for a moment
      drawStringCenteredNoShadow(fontRenderer, message, panelLeft + PANEL_W / 2, panelTop + 6, 0xFFA01E1E);
    else drawStringCenteredNoShadow(fontRenderer, LegacyRecipes.TAB_NAMES[tab], panelLeft + PANEL_W / 2, panelTop + 6, ConsoleUI.TEXT);

    int rowLeft = rowX(), rowTop = rowY();
    for (int i = 0; i < ROW; i++) {
      int groupIndex = rowStart + i;
      recipeButton(rowLeft + i * BUTTON, rowTop, groupIndex < groups.size() ? groups.get(groupIndex).current() : null);
    }
    // more buttons to the sides: arrows like the console's, and where you are in the row
    if (rowStart > 0) ConsoleUI.sideArrow(this, rowLeft - 8, rowTop + BUTTON / 2, -1);
    if (rowStart + ROW < groups.size()) ConsoleUI.sideArrow(this, rowLeft + ROW * BUTTON + 3, rowTop + BUTTON / 2, 1);
    if (groups.size() > ROW) {
      String pos = (selectedIndex + 1) + "/" + groups.size();
      drawStringNoShadow(fontRenderer, pos, panelLeft + PANEL_W - 8 - fontRenderer.stringWidth(pos), panelTop + 6, ConsoleUI.SHADOW);
    }
    if (onlyCraftable) drawStringNoShadow(fontRenderer, "Craftable", panelLeft + 8, panelTop + 6, ConsoleUI.SHADOW);

    // left panel: item name, ingredients, arrow, result
    int leftPanelX = panelLeft + GRID_X, panelsY = panelTop + BOTTOM_PANEL_Y;
    ConsoleUI.recess(this, leftPanelX, panelsY, leftPanelX + LEFT_PANEL_W, panelsY + BOTTOM_PANEL_H, ConsoleUI.RECESS);
    LegacyRecipes.Entry entry = selected();
    if (entry != null) {
      // the item name, centered in the part of the title row that the open variant column doesn't cover
      int nameLeft = leftPanelX + 4, nameRight = leftPanelX + LEFT_PANEL_W - 4;
      Group openGroup = selectedGroup();
      if (openGroup != null && openGroup.variants.size() > 1 && selectedIndex >= rowStart && selectedIndex < rowStart + ROW && rowY() + 2 * BUTTON + 10 > panelsY + 4) {
        int columnLeft = rowLeft + (selectedIndex - rowStart) * BUTTON - 4, columnRight = columnLeft + BUTTON + 8;
        if (columnRight > nameLeft && columnLeft < nameRight) {
          if (columnLeft + columnRight < nameLeft + nameRight) nameLeft = Math.max(nameLeft, columnRight + 2);
          else nameRight = Math.min(nameRight, columnLeft - 2);
        }
      }
      String name = trim(entry.output.getDisplayName(), nameRight - nameLeft);
      drawStringNoShadow(fontRenderer, name, (nameLeft + nameRight - fontRenderer.stringWidth(name)) / 2, panelsY + 5, ConsoleUI.TEXT);
    }
    int gridLeft = gridX(), gridTop = gridY();
    for (int y = 0; y < grid; y++)
      for (int x = 0; x < grid; x++) ConsoleUI.slot(this, gridLeft + x * CELL, gridTop + y * CELL, CELL);
    ConsoleUI.arrow(this, leftPanelX + ARROW_X, panelsY + ARROW_Y, ARROW_W, ARROW_H, ConsoleUI.RECESS_DARK);
    int resultLeft = resultX(), resultTop = resultY();
    ConsoleUI.slot(this, resultLeft, resultTop, RESULT);
    ItemStack[] stacks = inventory();
    if (entry != null) {
      int[] cells = entry.cells(grid);
      RecipeSymbol[] occurrences = entry.occurrences();
      View recipeView = view();
      int[] src = recipeView.sourceSlots; // the slots this craft would take (null: can't make it)
      boolean canCraft = src != null;
      selectedCan = canCraft;
      int focused = ConsoleUI.controller() && recipeView.changeable.length > 1 ? recipeView.changeable[focusAt(recipeView, focusSym)] : -1;
      for (int k = 0; k < cells.length; k++) {
        int cellX = gridLeft + (cells[k] % grid) * CELL, cellY = gridTop + (cells[k] / grid) * CELL;
        int choiceIndex = recipeView.choiceOf[k];
        ItemStack pin = choiceIndex >= 0 && recipeView.pins != null ? recipeView.pins[choiceIndex] : null;
        // craftable: exactly the item that will be used; otherwise the chosen one, or the options the player has
        ItemStack shownStack = canCraft && recipeView.used[k] != null ? recipeView.used[k] : pin != null ? pin : shown(occurrences[k], stacks);
        item(shownStack, cellX + 2, cellY + 2);
        if (choiceIndex >= 0 && choiceIndex == focused) { // the ingredient the right stick changes, when the recipe has several
          overlay(cellX + 1, cellY + 1, cellX + CELL - 1, cellY + 2, C_FOCUS);
          overlay(cellX + 1, cellY + CELL - 2, cellX + CELL - 1, cellY + CELL - 1, C_FOCUS);
          overlay(cellX + 1, cellY + 2, cellX + 2, cellY + CELL - 2, C_FOCUS);
          overlay(cellX + CELL - 2, cellY + 2, cellX + CELL - 1, cellY + CELL - 2, C_FOCUS);
        }
        if (!canCraft && recipeView.missing[k]) overlay(cellX + 1, cellY + 1, cellX + CELL - 1, cellY + CELL - 1, C_MISSING);
      }
      itemWithCount(entry.output, resultLeft + (RESULT - 16) / 2, resultTop + (RESULT - 16) / 2);
      if (!canCraft) overlay(resultLeft + 1, resultTop + 1, resultLeft + RESULT - 1, resultTop + RESULT - 1, C_FADE);
    }

    // right panel: "Inventory" and the whole inventory (hotbar a little apart)
    long now = System.currentTimeMillis();
    if (untidy) { message = "Inventory full: make room"; messageUntil = now + 600; }
    int rightPanelX = panelLeft + INV_X;
    ConsoleUI.recess(this, rightPanelX, panelsY, rightPanelX + RIGHT_PANEL_W, panelsY + BOTTOM_PANEL_H, ConsoleUI.RECESS);
    {
      drawStringCenteredNoShadow(fontRenderer, "Inventory", rightPanelX + RIGHT_PANEL_W / 2, panelsY + 4, ConsoleUI.TEXT);
      int inventoryX = rightPanelX + (RIGHT_PANEL_W - 9 * INVENTORY_CELL) / 2, inventoryY = panelsY + 15;
      for (int row = 0; row < 4; row++)
        for (int column = 0; column < 9; column++) {
          int slot = row < 3 ? 9 + row * 9 + column : column;          // three inventory rows, then the hotbar
          int x = inventoryX + column * INVENTORY_CELL, y = inventoryY + row * INVENTORY_CELL + (row == 3 ? 5 : 0);
          ConsoleUI.slot(this, x, y, INVENTORY_CELL);
          miniItem(mc.thePlayer.inventory.getItem(slot), x, y);
        }
    }

    // variant column of the selected slot, drawn last and in front (tab icons write depth) like on console
    Group group = selectedGroup();
    if (group != null && selectedIndex >= rowStart && selectedIndex < rowStart + ROW) {
      GLRenderer.modelM4f().translate(0f, 0f, 400f);
      int buttonX = rowLeft + (selectedIndex - rowStart) * BUTTON;
      if (group.variants.size() > 1) {
        boolean three = group.variants.size() > 2;
        int columnTop = three ? rowTop - BUTTON - 10 : rowTop - 4, columnBottom = rowTop + 2 * BUTTON + 10;
        ConsoleUI.panel(this, buttonX - 4, columnTop, buttonX + BUTTON + 4, columnBottom);
        if (three) {
          ConsoleUI.scrollArrow(this, buttonX + BUTTON / 2, columnTop + 3, -1);
          recipeButton(buttonX, rowTop - BUTTON, group.at(-1));
        }
        recipeButton(buttonX, rowTop + BUTTON, group.at(1));
        ConsoleUI.scrollArrow(this, buttonX + BUTTON / 2, columnBottom - 9, 1);
      }
      recipeButton(buttonX, rowTop, group.current(), selectedCan);
      ConsoleUI.highlight(this, buttonX, rowTop, BUTTON);
    }
  }
}
