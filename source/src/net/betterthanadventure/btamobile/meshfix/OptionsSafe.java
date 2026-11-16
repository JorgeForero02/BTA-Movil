package net.betterthanadventure.btamobile.meshfix;

/**
 * Reads a BTA Movil option without ever failing: if the options system cannot load (very early
 * startup, missing classes, tests) the default (true) is used. Kept outside MobileOptions so that
 * even a failure while initializing MobileOptions is caught here.
 */
public final class OptionsSafe {
  public static boolean get(String optionName) {
    try { return MobileOptions.optionOrDefault(optionName); }
    catch (Throwable ignored) { return true; }
  }

  /** A "(restart)" switch, read once at startup (the pattern of VertexPack.ON / Compact Vertices):
   *  -Dproperty=false forces it off, -Dproperty=true forces it on, otherwise the menu option decides. */
  public static boolean switchOn(String property, String option) { return resolve(System.getProperty(property), get(option)); }

  static boolean resolve(String property, boolean option) {
    if ("false".equalsIgnoreCase(property)) return false;
    if ("true".equalsIgnoreCase(property)) return true;
    return option;
  }

  public static int getInt(String optionName, int defaultValue) {
    try { return MobileOptions.intOptionOrDefault(optionName, defaultValue); }
    catch (Throwable ignored) { return defaultValue; }
  }
}
