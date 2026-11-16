package net.minecraft.client.gui;

/** Switch and rule for the on-screen keyboard fix (see KeyboardPatch). -Dbta.keyboardSync=false = vanilla BTA. */
public final class KeyboardSync {
  public static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("bta.keyboardSync"));

  private KeyboardSync() {}

  /** ScreenCreateWorld.tick: true when a field's text differs from the configuration (null-safe). */
  public static boolean differs(TextFieldElement name, TextFieldElement seed, net.minecraft.core.world.settings.WorldConfiguration configuration) {
    return !java.util.Objects.equals(name.getText(), configuration.getWorldName()) || !java.util.Objects.equals(seed.getText(), configuration.getStringSeed());
  }

  /** ScreenAddServer.tick: BTA's own rule from keyPressed (IP not empty), or the button as it was when off. */
  public static boolean saveEnabled(boolean current, String ip) { return ON ? ip != null && ip.length() > 0 : current; }
}
