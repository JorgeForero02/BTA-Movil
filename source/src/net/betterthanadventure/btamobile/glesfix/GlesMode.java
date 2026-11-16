package net.betterthanadventure.btamobile.glesfix;

/**
 * "Direct GLES" mode (default in BTA-Movil): LWJGL's desktop GL bindings resolve their function
 * pointers from the system OpenGL ES library instead of the launcher's GL translator.
 * Most GL 4.1 entry points used by BTA have identical names and ABI in GLES 3.2; the rest
 * are rerouted through {@link GlesShim}. Disable with -Dbta.gles=false.
 */
public final class GlesMode {
  public static boolean enabled = false;
  public static void boot() {
    if ("false".equalsIgnoreCase(System.getProperty("bta.gles"))) {
      System.out.println("[BTA-GLES] disabled by -Dbta.gles=false, using launcher renderer: " + System.getProperty("org.lwjgl.opengl.libname"));
      return;
    }
    String lib = System.getProperty("bta.gles.lib", "libGLESv3.so");
    System.out.println("[BTA-GLES] direct OpenGL ES mode: " + System.getProperty("org.lwjgl.opengl.libname") + " -> " + lib);
    System.setProperty("org.lwjgl.opengl.libname", lib);
    enabled = true;
  }
}
