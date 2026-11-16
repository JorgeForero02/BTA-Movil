package net.minecraft.client.gui;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.block.Blocks;
import net.minecraft.core.world.Dimension;
import net.minecraft.core.world.settings.WorldConfiguration;

/** BTA's on-screen keyboard (ScreenKeyboard) with the real screen code, without GL: writes into the
 *  fields with setText (bypassing keyPressed) and looks at what "Create World" and "Add Server" see.
 *  The JVM without the property requires cases 1-3 (synchronized); with -Dbta.keyboardSync=false it requires case 4
 *  (BTA behavior). The case is chosen by the property, never by KeyboardSync.ON. */
public class KeyboardTest {
  static final String WORLD_NAME = "Test world";
  static final String SEED = "seed123";
  static Object unsafe;
  static final List<String> failures = new ArrayList<>();

  static Object create(Class<?> c) throws Exception {
    return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, c);
  }

  static void setField(Object o, String field, Object value) throws Exception {
    Field f = o.getClass().getDeclaredField(field); f.setAccessible(true); f.set(o, value);
  }

  static Object readField(Object o, String field) throws Exception {
    Field f = o.getClass().getDeclaredField(field); f.setAccessible(true); return f.get(o);
  }

  static TextFieldElement textField(Screen s, String text) {
    TextFieldElement t = new TextFieldElement(s, null, 0, 0, 100, 20, "", "");
    t.setText(text);
    return t;
  }

  static ButtonElement button(boolean enabled) throws Exception {
    ButtonElement b = (ButtonElement) create(ButtonElement.class);
    b.enabled = enabled;
    return b;
  }

  static ScreenCreateWorld worldScreen(WorldConfiguration wc) throws Exception {
    ScreenCreateWorld s = (ScreenCreateWorld) create(ScreenCreateWorld.class);
    setField(s, "worldConfiguration", wc);
    setField(s, "textboxWorldName", textField(s, ""));
    setField(s, "textboxSeed", textField(s, ""));
    return s;
  }

  static ScreenAddServer serverScreen(boolean save, String ip) throws Exception {
    ScreenAddServer s = (ScreenAddServer) create(ScreenAddServer.class);
    setField(s, "ipTextField", textField(s, ip));
    setField(s, "serverNameTextField", textField(s, ""));
    setField(s, "buttonSave", button(save));
    return s;
  }

  static void typeWorld(ScreenCreateWorld s) throws Exception {
    ((TextFieldElement) readField(s, "textboxWorldName")).setText(WORLD_NAME);
    ((TextFieldElement) readField(s, "textboxSeed")).setText(SEED);
  }

  static void require(boolean ok, String msg) { if (!ok) failures.add(msg); }

  static void checkDefaults(WorldConfiguration wc, String caseName) {
    if (!"".equals(wc.getWorldName()) || !"".equals(wc.getStringSeed()))
      throw new IllegalStateException(caseName + ": unexpected default values (name='" + wc.getWorldName()
          + "', seed='" + wc.getStringSeed() + "')");
  }

  static boolean saveEnabled(ScreenAddServer s) throws Exception {
    return ((ButtonElement) readField(s, "buttonSave")).enabled;
  }

  public static void main(String[] a) throws Throwable {
    Field fu = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); fu.setAccessible(true); unsafe = fu.get(null);
    Blocks.init();
    Dimension.init();
    boolean off = "false".equalsIgnoreCase(System.getProperty("bta.keyboardSync"));

    // 1: Create World, by tick
    WorldConfiguration wc1 = new WorldConfiguration();
    checkDefaults(wc1, "case 1");
    ScreenCreateWorld s1 = worldScreen(wc1);
    typeWorld(s1);
    s1.tick();
    boolean tickName = WORLD_NAME.equals(wc1.getWorldName());
    boolean tickSeed = SEED.equals(wc1.getStringSeed());

    // 2: Create World, by buttonClicked (another screen, not going through tick)
    WorldConfiguration wc2 = new WorldConfiguration();
    checkDefaults(wc2, "case 2");
    ScreenCreateWorld s2 = worldScreen(wc2);
    typeWorld(s2);
    s2.buttonClicked(button(false));
    boolean clickName = WORLD_NAME.equals(wc2.getWorldName());
    boolean clickSeed = SEED.equals(wc2.getStringSeed());

    // 3: Add Server
    ScreenAddServer s3a = serverScreen(false, "x");
    s3a.tick();
    boolean enabled = saveEnabled(s3a);
    ScreenAddServer s3b = serverScreen(true, "");
    s3b.tick();
    boolean disabled = saveEnabled(s3b);

    // 5: tick only synchronizes if the text changed (each set notifies the configuration's changeListener)
    int[] changes = {0};
    WorldConfiguration wc5 = new WorldConfiguration();
    ScreenCreateWorld s5 = worldScreen(wc5);
    typeWorld(s5);
    s5.tick();
    wc5.setChangeListener(() -> changes[0]++);
    s5.tick();
    boolean still = changes[0] == 0;
    ((TextFieldElement) readField(s5, "textboxWorldName")).setText("Other world");
    s5.tick();
    boolean changed = changes[0] > 0 && "Other world".equals(wc5.getWorldName());

    if (!off) {
      require(still, "case 5 (tick): synchronizes with unchanged text (" + changes[0] + " notifications)");
      require(changed, "case 5 (tick): does not synchronize when the text changes");
      require(tickName, "case 1 (tick): name not synchronized, it is '" + wc1.getWorldName() + "'");
      require(tickSeed, "case 1 (tick): seed not synchronized, it is '" + wc1.getStringSeed() + "'");
      require(clickName, "case 2 (buttonClicked): name not synchronized, it is '" + wc2.getWorldName() + "'");
      require(clickSeed, "case 2 (buttonClicked): seed not synchronized, it is '" + wc2.getStringSeed() + "'");
      require(enabled, "case 3 (server): the Save button stays disabled with IP 'x'");
      require(!disabled, "case 3 (server): the Save button stays enabled with an empty IP");
    } else {
      // 4: with the switch off it is BTA: nothing is synchronized and the button stays as it was
      require(!tickName && !tickSeed, "case 4 (tick): synchronized with the switch off");
      require(!clickName && !clickSeed, "case 4 (buttonClicked): synchronized with the switch off");
      require(!enabled, "case 4 (server): the button changed to enabled with the switch off");
      require(disabled, "case 4 (server): the button changed to disabled with the switch off");
    }

    if (!failures.isEmpty()) {
      for (String f : failures) System.out.println("KeyboardTest: FAIL " + f);
      System.exit(1);
    }
    System.out.println(off
        ? "KeyboardTest: OK case 4 (switch off = BTA behavior)"
        : "KeyboardTest: OK cases 1-3 and 5 (tick, buttonClicked, server and tick without changes)");
  }
}
