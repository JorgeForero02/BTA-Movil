package net.betterthanadventure.btamobile.meshfix;

import net.minecraft.client.gui.options.components.BooleanOptionComponent;
import net.minecraft.client.gui.options.components.OptionsCategory;
import net.minecraft.client.gui.options.data.OptionsPages;
import net.minecraft.client.option.GameSettings;
import net.minecraft.client.option.OptionBoolean;
import net.minecraft.client.option.OptionRange;
import net.minecraft.client.gui.options.components.ToggleableOptionComponent;

/**
 * Options shown in Options > Video > "BTA Movil". Saved in options.txt like any other option.
 * -Dbta.caveCull=false / -Dbta.entityCull=false / -Dbta.mergeDraws=false / -Dbta.packedVertices=false / -Dbta.syncEdits=false
 * / -Dbta.asyncMesh=false / -Dbta.fluidGap=0.0001 force them off (safety switch); -Dbta.asyncMesh.threads=N overrides "Chunk Building Threads".
 */
public final class MobileOptions {
  public static final OptionBoolean CAVE_CULLING = GameSettings.register(new OptionBoolean("btaCaveCulling", true));
  public static final OptionBoolean ENTITY_CULLING = GameSettings.register(new OptionBoolean("btaEntityCulling", true));
  public static final OptionBoolean SMOOTH_DECORATION = GameSettings.register(new OptionBoolean("btaSmoothDecoration", true));
  public static final OptionBoolean HIDE_UNDECORATED = GameSettings.register(new OptionBoolean("btaHideUndecorated", true));
  public static final OptionBoolean LEGACY_CRAFTING = GameSettings.register(new OptionBoolean("btaLegacyCrafting", true));
  public static final OptionBoolean CONSOLE_MENUS = GameSettings.register(new OptionBoolean("btaConsoleMenus", true));
  /** Joins draw ranges that sit next to each other in the VBO (instant). */
  public static final OptionBoolean MERGE_DRAWS = GameSettings.register(new OptionBoolean("btaMergeDraws", true));
  /** Chunk-building threads: 0 = Auto, else 1 .. Mesher.maxAllowed() (up to 4, 2 cores always left
   *  free). Applied at once (Mesher.setThreads). Replaces the old on/off "Parallel Chunk Building". */
  public static final OptionRange MESH_THREADS = GameSettings.register(meshThreads());

  private static OptionRange meshThreads() {
    OptionRange option = new OptionRange("btaMeshThreads", 0, 0, Mesher.maxAllowed());
    option.withDisplayStringProvider((mc, i18n, opt) -> {
      if (Mesher.fixed()) return Mesher.threads() + " (-Dbta.asyncMesh.threads)";
      int threads = opt.value == null ? 0 : (Integer) opt.value;
      return threads <= 0 ? "Auto (" + Mesher.autoThreads() + ")" : String.valueOf(Math.min(threads, Mesher.maxAllowed()));
    });
    option.addOnChangeCallback((mc, opt) -> { try { Mesher.setThreads(opt.value == null ? 0 : (Integer) opt.value); } catch (Throwable ignored) { } });
    return option;
  }
  /** 24-byte terrain vertices; read once at startup (restart to change). */
  public static final OptionBoolean PACKED_VERTICES = GameSettings.register(new OptionBoolean("btaPackedVertices", true));
  /** Chunks meshed on worker threads; read once at startup. Off = BTA's synchronous rebuild. */
  public static final OptionBoolean ASYNC_MESH = GameSettings.register(new OptionBoolean("btaAsyncMesh", true));
  /** Water faces against ice/glass moved into the water + contact pass; read at startup. Off = BTA. */
  public static final OptionBoolean FLUID_EDGES = GameSettings.register(new OptionBoolean("btaFluidEdges", true));
  /** Sections changed by the player's own block break/place are rebuilt in the same frame (instant). */
  public static final OptionBoolean SYNC_EDITS = GameSettings.register(new OptionBoolean("btaSyncEdits", true));

  private static final boolean CAVE_FORCED_OFF = "false".equalsIgnoreCase(System.getProperty("bta.caveCull"));
  private static final boolean ENTITY_FORCED_OFF = "false".equalsIgnoreCase(System.getProperty("bta.entityCull"));
  private static Object menuAddedTo;

  public static boolean caveCulling() {
    return !CAVE_FORCED_OFF && CAVE_CULLING.isTrue();
  }

  public static boolean entityCulling() {
    return !ENTITY_FORCED_OFF && ENTITY_CULLING.isTrue();
  }

  public static boolean smoothDecoration() {
    return SMOOTH_DECORATION.isTrue();
  }

  public static boolean hideUndecorated() {
    return HIDE_UNDECORATED.isTrue();
  }

  public static boolean consoleMenus() {
    return CONSOLE_MENUS.isTrue();
  }

  /** Option value, or the default (true) when the options system is not available (tests, very early startup). */
  public static boolean optionOrDefault(String optionName) {
    try {
      switch (optionName) {
        case "mergeDraws": return MERGE_DRAWS.isTrue();
        case "packedVertices": return PACKED_VERTICES.isTrue();
        case "syncEdits": return SYNC_EDITS.isTrue();
        case "asyncMesh": return ASYNC_MESH.isTrue();
        case "fluidEdges": return FLUID_EDGES.isTrue();
        default: return true;
      }
    } catch (Throwable ignored) { return true; }
  }

  /** Integer option value, or the default when the options system is not available. */
  public static int intOptionOrDefault(String optionName, int defaultValue) {
    try {
      switch (optionName) {
        case "meshThreads": return MESH_THREADS.value == null ? defaultValue : MESH_THREADS.value;
        default: return defaultValue;
      }
    } catch (Throwable ignored) { return defaultValue; }
  }

  public static boolean legacyCrafting() {
    return LEGACY_CRAFTING.isTrue();
  }

  private static Object controlsAddedTo;

  /** Called right after OptionsPages.CONTROLS is created: Controls > "Console Style (Legacy)". */
  public static void addControlsCategory() {
    try {
      if (OptionsPages.CONTROLS == null || OptionsPages.CONTROLS == controlsAddedTo) return;
      controlsAddedTo = OptionsPages.CONTROLS;
      OptionsPages.CONTROLS.withComponent(new OptionsCategory("gui.options.page.controls.category.btaconsole")
          .withComponent(new BooleanOptionComponent(LEGACY_CRAFTING))
          .withComponent(new BooleanOptionComponent(CONSOLE_MENUS)));
    } catch (Throwable error) {
      System.out.println("[BTA-movil] could not add controls options: " + error);
    }
  }

  /** A saved value above what this phone allows (options.txt from another phone, fewer cores online)
   *  goes back to Auto, so the slider shows a valid position. */
  public static void clampMeshThreads() {
    try {
      Integer saved = MESH_THREADS.value;
      if (saved == null || saved < 0 || saved > MESH_THREADS.highest) MESH_THREADS.value = 0;
    } catch (Throwable ignored) { }
  }

  /** Called right after OptionsPages.VIDEO is created. */
  public static void addVideoCategory() {
    clampMeshThreads();
    try {
      if (OptionsPages.VIDEO == null || OptionsPages.VIDEO == menuAddedTo) return;
      menuAddedTo = OptionsPages.VIDEO;
      OptionsPages.VIDEO.withComponent(new OptionsCategory("gui.options.page.video.category.btamovil")
          .withComponent(new BooleanOptionComponent(CAVE_CULLING))
          .withComponent(new BooleanOptionComponent(ENTITY_CULLING))
          .withComponent(new BooleanOptionComponent(SMOOTH_DECORATION))
          .withComponent(new BooleanOptionComponent(HIDE_UNDECORATED))
          .withComponent(new BooleanOptionComponent(SYNC_EDITS))
          .withComponent(new BooleanOptionComponent(MERGE_DRAWS))
          .withComponent(new ToggleableOptionComponent<>(MESH_THREADS).applyOnRelease()) // threads change once, when the slider is let go
          .withComponent(new BooleanOptionComponent(PACKED_VERTICES))
          .withComponent(new BooleanOptionComponent(ASYNC_MESH))
          .withComponent(new BooleanOptionComponent(FLUID_EDGES)));
    } catch (Throwable error) {
      System.out.println("[BTA-movil] could not add video options: " + error);
    }
  }
}
