package net.minecraft.client.gui.container;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.input.controller.Button;
import net.minecraft.client.input.controller.ControllerInput;
import net.minecraft.client.render.renderer.GLRenderer;
import net.minecraft.client.render.renderer.State;
import net.minecraft.client.render.font.FontRenderer;

/**
 * Console (Legacy Console Edition) look, drawn with plain rectangles (no textures): rounded panels,
 * recessed boxes, the beige recipe buttons and the control guide with colored button glyphs.
 * Colors follow the console palette.
 */
public final class ConsoleUI {
  private ConsoleUI() { }

  public static final int PANEL = 0xFFC6C6C6, OUTLINE = 0xFF000000, LIGHT = 0xFFFFFFFF, SHADOW = 0xFF555555,
      RECESS = 0xFFA6A6A6, RECESS_DARK = 0xFF8B8B8B, RECESS_EDGE = 0xFF373737, TEXT = 0xFF404040,
            TAB_FILL = 0xFF9C9C9C, TAB_LIGHT = 0xFFD1D1D1;

  // 9-slice shapes of the console panel and tabs, in GUI units: 8 border rows/columns, then 1 that
  // repeats, then 8 more ( . none, # outline, W white, c panel gray, s shadow, l / f inactive tab).
  public static final String[] PANEL_SHAPE = {"...###########...", "..#WWWWWWWWWWW#..", ".#WWWWWWWWWWWWc#.", "#WWWccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWccccccccccsss#", ".#csssssssssssss#", "..#ssssssssssss#.", "...############.."};
  public static final String[] TAB_LEFT = {"...###########...", "..#WWWWWWWWWWW#..", ".#WWWWWWWWWWWWc#.", "#WWWccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss.", "#WWcccccccccccs..", "#WWccccccccccc...", "#WWccccccccccc...", "#WWccccccccccc..."};
  public static final String[] TAB_MIDDLE = {"...###########...", "..#WWWWWWWWWWW#..", ".#WWWWWWWWWWWWc#.", "#WWWccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", ".WWcccccccccccss.", ".WWcccccccccccs..", "...ccccccccccc...", "...ccccccccccc...", "...ccccccccccc..."};
  public static final String[] TAB_RIGHT = {"...###########...", "..#WWWWWWWWWWW#..", ".#WWWWWWWWWWWWc#.", "#WWWccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", "#WWcccccccccccss#", ".WWcccccccccccss#", ".WWcccccccccccss#", "...cccccccccccss#", "...cccccccccccss#", "...cccccccccccss#"};
  public static final String[] TAB_LOW = {"...###########...", "..#lllllllllll#..", ".#llllllllllllf#.", "#lllffffffffffss#", "#llfffffffffffss#", "#llfffffffffffss#", "#llfffffffffffss#", "#llfffffffffffss#", "#llfffffffffffss#", "#llfffffffffffss#", "#llfffffffffffss#", "#llfffffffffffss#", "#llfffffffffffss#", "#llfffffffffffss#", "#llfffffffffffss#", "#llfffffffffffss#", "#llfffffffffffss#"};

  private static int color(char ch) {
    switch (ch) {
      case '#': return OUTLINE;
      case 'W': return LIGHT;
      case 'c': return PANEL;
      case 's': return SHADOW;
      case 'l': return TAB_LIGHT;
      case 'f': return TAB_FILL;
      default: return 0;
    }
  }

  /** Draws a 9-slice shape (needs at least 16 x 16); runs of one color in a row are one rectangle. */
  public static void drawNinePatch(Gui gui, int x0, int y0, int x1, int y1, String[] pattern) {
    for (int r = 0; r < 17; r++) {
      int ya = r < 8 ? y0 + r : r == 8 ? y0 + 8 : y1 - (17 - r), yb = r == 8 ? y1 - 8 : ya + 1;
      if (yb <= ya) continue;
      String row = pattern[r];
      int start = 0;
      while (start < 17) {
        char ch = row.charAt(start);
        int end = start;
        while (end + 1 < 17 && row.charAt(end + 1) == ch) end++;
        int col = color(ch);
        if (col != 0) {
          int xa = start < 8 ? x0 + start : start == 8 ? x0 + 8 : x1 - (17 - start);
          int xb = end < 8 ? x0 + end + 1 : end == 8 ? x1 - 8 : x1 - (17 - end) + 1;
          if (xb > xa) gui.drawRect(xa, ya, xb, yb, col);
        }
        start = end + 1;
      }
    }
  }

  /** Console panel. */
  public static void panel(Gui gui, int x0, int y0, int x1, int y1) { drawNinePatch(gui, x0, y0, x1, y1, PANEL_SHAPE); }

  /** Square recessed area: dark edge top/left, white edge bottom/right (console recessed panel / slot). */
  public static void recess(Gui gui, int x0, int y0, int x1, int y1, int fill) {
    gui.drawRect(x0, y0, x1, y1, fill);
    gui.drawRect(x0, y0, x1 - 1, y0 + 1, RECESS_EDGE);
    gui.drawRect(x0, y0 + 1, x0 + 1, y1 - 1, RECESS_EDGE);
    gui.drawRect(x0 + 1, y1 - 1, x1, y1, LIGHT);
    gui.drawRect(x1 - 1, y0, x1, y1 - 1, LIGHT);
  }

  /** A slot (icon holder): recessed, darker gray. */
  public static void slot(Gui gui, int x, int y, int size) { recess(gui, x, y, x + size, y + size, RECESS_DARK); }

  /** Small solid triangle pointing up (dir < 0) or down (dir > 0), centered on cx. */
  public static void triangle(Gui gui, int cx, int y, int dir, int color) {
    for (int i = 0; i < 3; i++) {
      int row = dir < 0 ? y + i : y + 2 - i;
      gui.drawRect(cx - i, row, cx + i + 1, row + 1, color);
    }
  }

  /** Selected slot: dark / light green-gray / dark frame, like the console's selection. */
  public static void highlight(Gui gui, int x, int y, int size) {
    gui.drawBox(x - 2, y - 2, x + size + 2, y + size + 2, 0xFF2B3429, 1);
    gui.drawBox(x - 1, y - 1, x + size + 1, y + size + 1, 0xFFA7B7A3, 1);
    gui.drawBox(x, y, x + size, y + size, 0xFF485445, 1);
  }

  /** Embossed scroll arrow (up dir < 0, down dir > 0), 7 wide, as on the console's variant column. */
  public static void scrollArrow(Gui gui, int cx, int y, int dir) {
    for (int i = 0; i < 4; i++) {
      int row = dir < 0 ? y + i : y + 3 - i;
      gui.drawRect(cx - i, row, cx + i + 1, row + 1, SHADOW);
    }
    if (dir < 0) gui.drawRect(cx - 3, y + 4, cx + 4, y + 5, LIGHT);
    else gui.drawRect(cx - 1, y + 4, cx + 2, y + 5, LIGHT);
  }

  /** Embossed side scroll arrow (left dir < 0, right dir > 0), 4 wide and 7 tall, centered on cy. */
  public static void sideArrow(Gui gui, int x, int cy, int dir) {
    for (int i = 0; i < 4; i++) {
      int col = dir < 0 ? x + i : x + 3 - i;
      gui.drawRect(col, cy - i, col + 1, cy + i + 1, SHADOW);
    }
    if (dir < 0) gui.drawRect(x + 4, cy - 3, x + 5, cy + 4, LIGHT); // light edge on the flat side
    else gui.drawRect(x - 1, cy - 3, x, cy + 4, LIGHT);
  }

  /** Console arrow pointing right, w x h (shaft a third of the height, head half the width). */
  public static void arrow(Gui gui, int x, int y, int w, int h, int color) {
    int head = h / 2 + 1, shaftH = Math.max(3, h / 3), sy = y + (h - shaftH) / 2;
    gui.drawRect(x, sy, x + w - head, sy + shaftH, color);
    int hx = x + w - head;
    for (int i = 0; i < head; i++) {
      int half = (h - 1) / 2 - (i * ((h - 1) / 2)) / Math.max(1, head - 1);
      gui.drawRect(hx + i, y + h / 2 - half, hx + i + 1, y + h / 2 + half + 1, color);
    }
  }

  // ---------------------------------------------------------------- control guide

  /** Special icons for guide entries (otherwise the entry's icon is a Button, or a Button[] pair). */
  public static final String STICK = "stick";

  /**
   * Draws one button icon from the game's own controller sheet (gui/controller/<type>.png, which has
   * Xbox 360 / Xbox One / PlayStation / Switch / Steam versions) and returns its width.
   * The icon follows the physical button, so swapped A/B, X/Y or shoulder settings stay correct.
   */
  public static int icon(Gui gui, Minecraft mc, ControllerInput input, Object spec, int x, int y) {
    if (spec instanceof Button[]) {
      int w = 0;
      for (Button button : (Button[]) spec) w += icon(gui, mc, input, button, x + w, y) + 1;
      return w - 1;
    }
    int u, v;
    if (spec == STICK) { u = 1; v = 149; }
    else if (spec instanceof Button) {
      Button button = (Button) spec;
      if (button == input.buttonLeftTrigger || button == input.buttonRightTrigger) { u = 1 + 15 * (button.button == 4 ? 2 : 3); v = 134; }
      else {
        int id = button.button;
        if (id >= 0 && id <= 3) { u = 1 + 15 * id; v = 119; }
        else if (id == 4 || id == 5) { u = 1 + 15 * (id - 4); v = 134; }
        else if (id == 6) { u = 1 + 15 * 7; v = 134; }       // back / view / select
        else if (id == 7) { u = 1 + 15 * 6; v = 134; }       // start / menu
        else if (id == 9 || id == 10) { u = 1 + 15 * (id - 5); v = 134; } // stick clicks
        else if (id >= 11 && id <= 14) { u = 1 + 15 * (id - 7); v = 119; } // d-pad
        else return 0;
      }
    } else return 0;
    String type;
    try { type = input.getType().getAssetName(); } catch (Throwable ignored) { type = "xbox_360"; }
    mc.textureManager.loadTexture("/assets/minecraft/textures/gui/controller/" + type + ".png").bind();
    GLRenderer.enableState(State.BLEND);
    GLRenderer.setColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    gui.drawTexturedModalRect(x, y, u, v, 14, 14);
    GLRenderer.disableState(State.BLEND);
    return 14;
  }

  private static int iconWidth(Object spec) {
    if (spec instanceof Button[]) return ((Button[]) spec).length * 15 - 1;
    return 14;
  }

  /**
   * Control guide like on console: entries of {icon, action}, starting at x = 8 on row y and wrapping
   * upwards whenever an entry would pass maxRight.
   */
  public static void guide(Gui gui, Minecraft mc, FontRenderer font, int maxRight, int y, List<Object[]> entries) {
    ControllerInput input = mc.controllerInput;
    if (input == null) return;
    int x = 8;
    for (Object[] entry : entries) {
      String action = (String) entry[1];
      int w = iconWidth(entry[0]) + 3, tw = font.stringWidth(action);
      if (x > 8 && x + w + tw > maxRight) { x = 8; y -= 17; }
      icon(gui, mc, input, entry[0], x, y);
      gui.drawStringShadow(font, action, x + w, y + 3, 0xFFFFFFFF);
      x += w + tw + 11;
    }
    GLRenderer.setColor4f(1.0f, 1.0f, 1.0f, 1.0f);
  }

  /** How many rows guide() would use. */
  public static int guideLines(FontRenderer font, int maxRight, List<Object[]> entries) {
    int x = 8, lines = 1;
    for (Object[] entry : entries) {
      int w = iconWidth(entry[0]) + 3, tw = font.stringWidth((String) entry[1]);
      if (x > 8 && x + w + tw > maxRight) { x = 8; lines++; }
      x += w + tw + 11;
    }
    return lines;
  }

  public static boolean controller() {
    Minecraft mc = Minecraft.getMinecraft();
    return mc.controllerInput != null; // a gamepad is connected (the game detects it on its own)
  }
}
