package net.minecraft.client.gui.container;

import java.util.ArrayList;
import java.util.List;
import net.betterthanadventure.btamobile.meshfix.MobileOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.input.controller.Button;
import net.minecraft.client.input.controller.ControllerInput;
import net.minecraft.client.render.renderer.GLRenderer;
import net.minecraft.core.item.ItemStack;
import net.minecraft.core.player.inventory.slot.Slot;
import net.minecraft.core.player.inventory.slot.SlotResult;

/**
 * Console look for the vanilla container screens (crafting table, furnaces, trommel, chests, dispenser,
 * activator, inventory) without touching their layout:
 *  - the frame of the background texture is replaced by a rounder console panel (the texture's inside,
 *    with all its art and progress sprites, is still drawn), see {@link #blit};
 *  - a console control guide with what each button does on the slot under the cursor, see {@link #overlay}.
 * All of it only with a controller connected and "Console Menus and Buttons" on (Options > Controls > Console Style (Legacy)).
 */
public final class ConsoleSkin {
  private ConsoleSkin() { }

  private static final int FRAME = 4; // texture frame trimmed on each side (outline + bevel + corner pixel)

  /**
   * Replaces Gui.drawTexturedModalRect(x, y, u, v, w, h) inside drawGuiContainerBackgroundLayer.
   * Pieces with u == 0 are the panel background (chests draw it in several pieces); anything else
   * (flames, arrows, selectors) is drawn untouched.
   */
  public static void blit(Gui gui, int x, int y, int u, int v, int w, int h) {
    if (u != 0 || !(gui instanceof ScreenContainerAbstract) || !MobileOptions.consoleMenus() || !ConsoleUI.controller()) {
      gui.drawTexturedModalRect(x, y, u, v, w, h);
      return;
    }
    ScreenContainerAbstract screen = (ScreenContainerAbstract) gui;
    int left = (screen.width - screen.xSize) / 2, top = (screen.height - screen.ySize) / 2;
    if (x != left || w != screen.xSize) { // not a full-width background piece: leave it alone
      gui.drawTexturedModalRect(x, y, u, v, w, h);
      return;
    }
    boolean isFirstPiece = y <= top, isLastPiece = y + h >= top + screen.ySize;
    if (isFirstPiece) {
      panel(gui, left, top, left + screen.xSize, top + screen.ySize);
      GLRenderer.setColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }
    int trimTop = isFirstPiece ? FRAME : 0, trimBottom = isLastPiece ? FRAME : 0;
    if (h - trimTop - trimBottom > 0) gui.drawTexturedModalRect(x + FRAME, y + trimTop, u + FRAME, v + trimTop, w - 2 * FRAME, h - trimTop - trimBottom);
  }

  /** Rounded console panel (see ConsoleUI.panel). */
  public static void panel(Gui gui, int x0, int y0, int x1, int y1) { ConsoleUI.panel(gui, x0, y0, x1, y1); }

  // ---------------------------------------------------------------- console X / Y in containers

  /** True when the open screen is a vanilla container that should use the console button layout. */
  private static boolean consoleButtons() {
    try {
      Minecraft mc = Minecraft.getMinecraft();
      Object screen = mc.currentScreen;
      return screen instanceof ScreenContainerAbstract && !(screen instanceof ScreenLegacyCrafting) && !(screen instanceof ScreenInventoryCreative)
          && MobileOptions.consoleMenus() && ConsoleUI.controller();
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** The button that does BTA's "right click" (take half / place one): X on console, Y in vanilla BTA. */
  public static Button consoleX(ControllerInput input) { return consoleButtons() ? input.buttonX : input.buttonY; }

  /** The button that does BTA's "shift click" (quick move): Y on console, X in vanilla BTA. */
  public static Button consoleY(ControllerInput input) { return consoleButtons() ? input.buttonY : input.buttonX; }

  /** Replaces the GETFIELD ControllerInput.buttonY in Screen.guiSpecificControllerInput. */
  public static Button rightClickButton(ControllerInput input) { return consoleX(input); }

  /** Replaces the GETFIELD ControllerInput.buttonX in Screen.guiSpecificControllerInput. */
  public static Button shiftClickButton(ControllerInput input) { return consoleY(input); }

  // ---------------------------------------------------------------- control guide

  /** Called at the end of ScreenContainerAbstract.render. */
  public static void overlay(ScreenContainerAbstract screen) {
    try {
      if (screen instanceof ScreenLegacyCrafting || screen instanceof ScreenInventoryCreative) return;
      if (!MobileOptions.consoleMenus() || !ConsoleUI.controller()) return;
      Minecraft mc = screen.mc;
      ControllerInput input = mc.controllerInput;
      Slot hovered = input.getSlotAtCursor(screen);
      ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.inventory.getHeldItemStack();
      ItemStack inSlot = hovered == null ? null : hovered.getItemStack();
      boolean result = hovered instanceof SlotResult;

      // console layout: A main action, X the "off" action (half / one), Y quick move, B exit
      Button offButton = consoleX(input), quickButton = consoleY(input);
      List<Object[]> guideEntries = new ArrayList<>();
      if (held != null) {
        if (hovered != null && !result) {
          boolean same = inSlot != null && inSlot.isItemEqual(held);
          guideEntries.add(new Object[]{input.buttonA, inSlot == null || same ? (held.stackSize > 1 ? "Place All" : "Place") : "Swap"});
          if ((inSlot == null || same) && held.stackSize > 1) guideEntries.add(new Object[]{offButton, "Place One"});
        } else if (hovered == null) {
          guideEntries.add(new Object[]{input.buttonA, held.stackSize > 1 ? "Drop All" : "Drop"});
        }
      } else if (inSlot != null) {
        guideEntries.add(new Object[]{input.buttonA, result ? "Create" : "Take"});
        if (!result && inSlot.stackSize > 1) guideEntries.add(new Object[]{offButton, "Take Half"});
        guideEntries.add(new Object[]{quickButton, result ? "Create All" : "Quick Move"});
      }
      guideEntries.add(new Object[]{input.buttonB, "Exit"});
      if (screen instanceof ScreenCrafting || screen instanceof ScreenInventory || screen instanceof ScreenFurnace || screen instanceof ScreenFurnaceBlast) {
        guideEntries.add(new Object[]{new Button[]{input.buttonLeftTrigger, input.buttonRightTrigger}, "Jump"});
      }

      // bottom left like on console; if the panel reaches the bottom, stack it in the left margin instead
      int top = (screen.height - screen.ySize) / 2, bottom = top + screen.ySize, left = (screen.width - screen.xSize) / 2;
      int lines = ConsoleUI.guideLines(screen.fontRenderer, screen.width - 8, guideEntries);
      if (screen.height - 22 - (lines - 1) * 17 > bottom + 2 || left < 80) ConsoleUI.guide(screen, mc, screen.fontRenderer, screen.width - 8, screen.height - 22, guideEntries);
      else ConsoleUI.guide(screen, mc, screen.fontRenderer, left - 4, screen.height - 22, guideEntries);
    } catch (Throwable ignored) {
      // never break the vanilla screen because of the guide
    }
  }
}
