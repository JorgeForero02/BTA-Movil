package net.betterthanadventure.btamobile.meshfix;

/** Menu switches read once at startup ("(restart)" options, like Compact Vertices): -Dbta.x=false forces
 *  off, -Dbta.x=true forces on, otherwise the menu option decides. Covers "Background Chunk Building"
 *  (Mesher.ENABLED) and "Water Edge Fix" (FluidInset.GAP; off = BTA's 0.0001). */
public class OptionSwitchesTest {
  public static void main(String[] a) {
    boolean ok = true;
    StringBuilder failures = new StringBuilder();
    String[] props = { null, "false", "true", "FALSE", "other" };
    boolean[][] expected = { { false, true }, { false, false }, { true, true }, { false, false }, { false, true } };
    for (int i = 0; i < props.length; i++)
      for (int o = 0; o < 2; o++)
        if (OptionsSafe.resolve(props[i], o == 1) != expected[i][o]) { ok = false; failures.append(" resolve(" + props[i] + "," + (o == 1) + ")"); }
    if (FluidInset.gap(null, true) != 0.005) { ok = false; failures.append(" water-on"); }
    if (FluidInset.gap(null, false) != 1.0E-4) { ok = false; failures.append(" water-off-is-BTA"); }
    if (FluidInset.gap("0.01", false) != 0.01) { ok = false; failures.append(" -Dbta.fluidGap-wins"); }
    if (FluidInset.gap("abc", true) != 0.005 || FluidInset.gap("abc", false) != 1.0E-4) { ok = false; failures.append(" invalid-value"); }
    if (FluidInset.gap("9", true) != 0.25 || FluidInset.gap("0", true) != 1.0E-4) { ok = false; failures.append(" limits"); }
    System.out.println("OptionSwitchesTest: " + (ok ? "OK (-Dbta.x=false/true wins; otherwise the menu option; water OFF = BTA)" : "FAIL:" + failures));
    if (!ok) System.exit(1);
  }
}
