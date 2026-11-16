package net.minecraft.client.gui.container;

import net.minecraft.core.data.registry.recipe.RecipeSymbol;

import net.betterthanadventure.btamobile.meshfix.MobileOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.input.InputType;
import net.minecraft.client.input.controller.ControllerInput;
import net.minecraft.core.InventoryAction;
import net.minecraft.core.item.ItemStack;
import net.minecraft.core.player.inventory.container.Container;
import net.minecraft.core.player.inventory.menu.MenuAbstract;
import net.minecraft.core.player.inventory.menu.MenuCrafting;
import net.minecraft.core.player.inventory.menu.MenuInventory;
import net.minecraft.core.player.inventory.slot.Slot;
import net.minecraft.core.player.inventory.slot.SlotResult;

/**
 * Legacy (console) crafting: hooks and the crafting itself.
 *
 * Crafting is done exactly as a player would do it by hand, through the open menu's normal clicks
 * (PlayerController.handleInventoryMouseClick): put the ingredients in the grid one by one, take the
 * result with the "craft one" action, and move whatever is left in the grid back to the inventory.
 * So the game's own rules decide everything (and in multiplayer the server checks every click):
 * nothing can be duplicated or lost.
 */
public final class LegacyCrafting {
  private LegacyCrafting() { }

  public static boolean optionOn() {
    try { return MobileOptions.legacyCrafting(); } catch (Throwable ignored) { return false; }
  }

  /**
   * Called at the start of Minecraft.displayScreen. With a controller and the option on:
   *  - a crafting table opens the Legacy crafting screen (3x3);
   *  - X in game opens the personal Legacy crafting (2x2) like on console; Y still opens the inventory
   *    (BTA opens the inventory with both, so the inventory screen is swapped only when X was pressed).
   */
  public static Screen wrap(Screen screen) {
    try {
      if (screen != null && optionOn()) {
        Minecraft mc = Minecraft.getMinecraft();
        boolean hasGamepad = mc.controllerInput != null; // gamepad connected: plug it in and the console menus appear
        if (hasGamepad && screen.getClass() == ScreenCrafting.class && supported(((ScreenContainerAbstract) screen).inventorySlots)) {
          mc.controllerInput.cancelButtonPresses(); // the press that opened the table must not act inside it
          return new ScreenLegacyCrafting((ScreenContainerAbstract) screen);
        }
        if (hasGamepad && screen.getClass() == ScreenInventory.class && mc.currentScreen == null && mc.controllerInput.buttonX.pressedThisFrame()
            && supported(((ScreenContainerAbstract) screen).inventorySlots)) {
          mc.controllerInput.cancelButtonPresses(); // the X that opened it must not also "Create All"
          return new ScreenLegacyCrafting((ScreenContainerAbstract) screen);
        }
      }
    } catch (Throwable error) {
      System.out.println("[BTA-movil] legacy crafting not opened: " + error);
    }
    return screen;
  }



  static boolean supported(MenuAbstract menu) {
    return menu != null && (menu.getClass() == MenuCrafting.class || menu.getClass() == MenuInventory.class);
  }

  /** Shows another screen for the same open menu without closing the menu (no removed() call). */
  static void switchTo(Screen target) {
    Minecraft mc = Minecraft.getMinecraft();
    mc.currentScreen = target;
    target.opened(mc.resolution.getScaledWidthScreenCoords(), mc.resolution.getScaledHeightScreenCoords());
    if (mc.controllerInput != null) {
      mc.controllerInput.hideCursor = target instanceof ScreenLegacyCrafting;
      mc.controllerInput.cursorX = mc.resolution.getScaledWidthScreenCoords() / 2.0;
      mc.controllerInput.cursorY = mc.resolution.getScaledHeightScreenCoords() / 2.0;
    }
  }

  // ---------------------------------------------------------------- the open menu, by slot role

  /** Slot numbers of the open menu: result, grid cells (row major), the 36 inventory slots. */
  static final class Layout {
    final MenuAbstract menu;
    final int grid;
    int result = -1;
    final int[] cells;
    final int[] inv = new int[36];

    Layout(MenuAbstract menu, Container craftSlots, Container playerInv, int grid) {
      this.menu = menu;
      this.grid = grid;
      this.cells = new int[grid * grid];
      java.util.Arrays.fill(cells, -1);
      java.util.Arrays.fill(inv, -1);
      for (Slot slot : menu.slots) {
        if (slot instanceof SlotResult) { result = slot.index; continue; }
        for (int i = 0; i < cells.length; i++) if (slot.isAt(craftSlots, i)) cells[i] = slot.index;
        for (int i = 0; i < 36; i++) if (slot.isAt(playerInv, i)) inv[i] = slot.index;
      }
    }

    boolean valid() {
      if (result < 0) return false;
      for (int cell : cells) if (cell < 0) return false;
      for (int cell : inv) if (cell < 0) return false;
      return true;
    }

    ItemStack stack(int menuSlot) { return menu.getSlot(menuSlot).getItemStack(); }

    ItemStack[] inventory() {
      ItemStack[] stacks = new ItemStack[36];
      for (int i = 0; i < 36; i++) stacks[i] = stack(inv[i]);
      return stacks;
    }

    boolean gridEmpty() {
      for (int cell : cells) if (stack(cell) != null) return false;
      return true;
    }
  }

  static Layout layout(MenuAbstract menu) {
    Container playerInventory = player().inventory;
    Layout view;
    if (menu instanceof MenuCrafting) view = new Layout(menu, ((MenuCrafting) menu).craftSlots, playerInventory, 3);
    else if (menu instanceof MenuInventory) view = new Layout(menu, ((MenuInventory) menu).craftSlots, playerInventory, 2);
    else return null;
    return view.valid() ? view : null;
  }

  // ---------------------------------------------------------------- crafting by clicks

  public static final String OK = null;

  /** Offline tests only: when set, clicks go straight to the menu for this player (no Minecraft instance). */
  static net.minecraft.core.entity.player.Player testPlayer;

  private static net.minecraft.core.entity.player.Player player() {
    return testPlayer != null ? testPlayer : Minecraft.getMinecraft().thePlayer;
  }

  private static void click(Layout view, InventoryAction action, int menuSlot) {
    int[] args = new int[]{menuSlot, 0};
    if (testPlayer != null) { view.menu.clicked(action, args, testPlayer); return; }
    if (multiplayer()) packetTokens -= 1.0; // every click is one packet to the server
    Minecraft mc = Minecraft.getMinecraft();
    mc.playerController.handleInventoryMouseClick(view.menu.containerId, action, args, mc.thePlayer);
  }

  private static ItemStack held() { return player().inventory.getHeldItemStack(); }

  /** Puts whatever is on the cursor back into the inventory. */
  private static boolean putBackHeld(Layout view, int preferred) {
    if (held() == null) return true;
    if (preferred >= 0) click(view, InventoryAction.CLICK_LEFT, view.inv[preferred]);
    for (int pass = 0; pass < 2 && held() != null; pass++) {
      for (int i = 0; i < 36 && held() != null; i++) {
        ItemStack slotStack = view.stack(view.inv[i]);
        ItemStack heldStack = held();
        boolean fits = pass == 0 ? (slotStack != null && slotStack.canStackWith(heldStack) && slotStack.stackSize < slotStack.getMaxStackSize()) : slotStack == null;
        if (fits) click(view, InventoryAction.CLICK_LEFT, view.inv[i]);
      }
    }
    return held() == null;
  }

  /** Moves everything in the grid back to the inventory (shift-click). */
  private static boolean clearGrid(Layout view) {
    for (int cell : view.cells) if (view.stack(cell) != null) click(view, InventoryAction.MOVE_STACK, cell);
    return view.gridEmpty();
  }

  /** Crafts the recipe once. Returns null on success or a short reason. */
  public static String craftOnce(MenuAbstract menu, LegacyRecipes.Entry entry) { return craftOnce(menu, entry, (RecipeSymbol[]) null, null); }

  public static String craftOnce(MenuAbstract menu, LegacyRecipes.Entry entry, RecipeSymbol pinSym, ItemStack pin) {
    return pin == null ? craftOnce(menu, entry) : craftOnce(menu, entry, new RecipeSymbol[]{pinSym}, new ItemStack[]{pin});
  }

  /** Crafts once; pinSyms / pins: the ingredients the player chose for those symbols (null = any). */
  public static String craftOnce(MenuAbstract menu, LegacyRecipes.Entry entry, RecipeSymbol[] pinSyms, ItemStack[] pins) {
    Layout view = layout(menu);
    if (view == null) return "Not available here";
    if (!entry.fits(view.grid)) return "Needs a crafting table";
    if (!putBackHeld(view, -1)) return "Inventory full";
    if (!clearGrid(view)) return "Inventory full";

    ItemStack[] inventoryStacks = view.inventory();
    int[] sources = LegacyRecipes.plan(entry, inventoryStacks, pinSyms, pins);
    if (sources == null) return "Missing ingredients";
    if (!roomForOutput(inventoryStacks, sources, entry.output)) return "Inventory full"; // checked before touching anything
    int[] gridCells = entry.cells(view.grid);

    // place one item per cell, picking each source stack up once
    boolean[] done = new boolean[sources.length];
    for (int k = 0; k < sources.length; k++) {
      if (done[k]) continue;
      int from = sources[k];
      click(view, InventoryAction.CLICK_LEFT, view.inv[from]);          // pick up the whole stack
      for (int j = k; j < sources.length; j++) {
        if (done[j] || sources[j] != from) continue;
        if (held() == null) break;
        click(view, InventoryAction.CLICK_RIGHT, view.cells[gridCells[j]]); // place exactly one
        done[j] = true;
      }
      if (!putBackHeld(view, from)) { clearGrid(view); return "Inventory full"; }
    }
    for (int j = 0; j < sources.length; j++) {
      if (!done[j] || view.stack(view.cells[gridCells[j]]) == null) { clearGrid(view); putBackHeld(view, -1); return "Could not place ingredients"; }
    }

    // the grid must now produce what the screen showed
    ItemStack result = view.stack(view.result);
    if (result == null || result.itemID != entry.output.itemID
        || (entry.output.getMetadata() != -1 && result.getMetadata() != entry.output.getMetadata())) {
      clearGrid(view);
      return "Recipe not recognised";
    }
    int before = countInInventory(view, entry.output);
    click(view, InventoryAction.MOVE_SINGLE_ITEM, view.result);          // craft one, straight into the inventory
    boolean crafted = countInInventory(view, entry.output) > before;
    clearGrid(view);                                                   // leftovers (buckets, tools...) go back
    putBackHeld(view, -1);
    return crafted ? OK : "Inventory full";
  }

  /**
   * True if the crafted items will fit in the inventory once the planned ingredients are taken: an
   * empty slot (or one the ingredients empty), or a stack of the same item with room for them.
   * Otherwise BTA's "craft into inventory" click does nothing and the ingredients would have been
   * moved around for nothing. Pure (no game state).
   */
  static boolean roomForOutput(ItemStack[] slots, int[] sourceSlots, ItemStack output) {
    if (output == null) return true;
    int[] used = new int[slots.length];
    for (int i : sourceSlots) used[i]++;
    int need = Math.max(1, output.stackSize), room = 0;
    for (int i = 0; i < slots.length; i++) {
      ItemStack stack = slots[i];
      if (stack == null || stack.stackSize - used[i] <= 0) return true;
      if (stack.itemID == output.itemID && (output.getMetadata() == -1 || stack.getMetadata() == output.getMetadata())) {
        try { if (stack.hasCustomName()) continue; } catch (Throwable ignored) { } // a renamed stack does not merge
        room += Math.max(0, stack.getMaxStackSize() - (stack.stackSize - used[i])); // shift-click spreads over all of them
        if (room >= need) return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- multiplayer packet budget

  /**
   * In multiplayer every click is a packet, and servers kick clients that send too many: Paper's
   * default packet limiter allows 500 packets in 7 seconds (about 70 per second) per player, and its
   * recipe-spam limiter kicks for fast recipe clicks. "Create All" therefore spends a token bucket of
   * clicks: 30 per second on average, bursts of 36, and a craft only starts when the bucket holds its
   * worst case (3 per grid cell: pick up, place, put back; + 6), so it does not overdraw. With the game's own ~20 movement packets
   * per second that stays near 50 per second (about 380 in 7 seconds), under the limit.
   */
  static final double PACKETS_PER_SECOND = 30.0, PACKET_BURST = 36.0;
  private static double packetTokens = PACKET_BURST;
  private static long packetsAt;

  /** Worst-case clicks for one craft of this recipe. */
  static int clickCost(LegacyRecipes.Entry entry) {
    int cellCount = 9;
    try { cellCount = entry.occurrences().length; } catch (Throwable ignored) { }
    return 3 * cellCount + 6;
  }

  /** True if another craft of this recipe may start now (single player: always). */
  public static boolean mayCraftNow(LegacyRecipes.Entry entry) {
    if (!multiplayer()) return true;
    long now = System.nanoTime();
    if (packetsAt != 0) packetTokens = Math.min(PACKET_BURST, packetTokens + (now - packetsAt) / 1e9 * PACKETS_PER_SECOND);
    packetsAt = now;
    return packetTokens >= Math.min(PACKET_BURST, clickCost(entry));
  }

  private static int countInInventory(Layout view, ItemStack like) {
    int total = 0;
    for (int i = 0; i < 36; i++) {
      ItemStack slotStack = view.stack(view.inv[i]);
      if (slotStack != null && slotStack.itemID == like.itemID && (like.getMetadata() == -1 || slotStack.getMetadata() == like.getMetadata())) total += slotStack.stackSize;
    }
    return total;
  }

  /** Puts anything left in the grid or on the cursor back into the inventory. Returns true if all is tidy. */
  public static boolean tidy(MenuAbstract menu) {
    Layout view = layout(menu);
    if (view == null) return true;
    if (held() == null && view.gridEmpty()) return true;
    putBackHeld(view, -1);
    clearGrid(view);
    return held() == null && view.gridEmpty();
  }

  /** In multiplayer every click is a packet: craft at most once per frame there. */
  public static boolean multiplayer() {
    try { return testPlayer == null && Minecraft.getMinecraft().currentWorld != null && Minecraft.getMinecraft().currentWorld.isClientSide; }
    catch (Throwable ignored) { return true; }
  }
}
