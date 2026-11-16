package net.betterthanadventure.btamobile.glesfix;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL41;

/**
 * Checks the OpenGL ES version once, before the first shader is compiled (VertexPack.glShaderSource,
 * right after the window/context exists).
 *
 * BTA itself checks for desktop OpenGL 4.1 and only logs "BTA Requires OpenGL4.1 but only OpenGL ES ...
 * was found!" (expected here: BTA Movil runs on OpenGL ES 3.2). BTA Movil's shaders are GLSL ES 3.20
 * ("#version 320 es"), which an OpenGL ES 3.0/3.1 context cannot compile; without this check such a
 * phone fails later with shader compile errors that do not say why. Like BTA's own requirement check,
 * this names the version needed, the version found and the GPU (GL_RENDERER), but stops: the game cannot render without them.
 */
public final class GlesCheck {
  private GlesCheck() { }
  private static boolean done;
  private static final Pattern ES = Pattern.compile("OpenGL ES (\\d+)\\.(\\d+)");

  public static void once() {
    if (done) return;
    done = true;
    if (!GlesMode.enabled) return;
    String version;
    try { version = GL41.glGetString(0x1F02); } catch (Throwable ignored) { return; }
    String renderer;
    try { renderer = GL41.glGetString(0x1F01); } catch (Throwable ignored) { renderer = null; } // GL_RENDERER: only for the message
    String result = verdict(version, renderer);
    if (result == null) System.out.println("[BTA-GLES] " + version + ": OK (BTA's \"Requires OpenGL4.1\" line above is expected with OpenGL ES)");
    else if (result.startsWith("WARN")) System.out.println("[BTA-GLES] " + result.substring(5));
    else { System.out.println("[BTA-GLES] " + result); throw new Error(result); } // an Error: no catch (Exception) on the way can swallow it
  }

  /** Pure (tested): null = OK, "WARN ..." = only a warning, anything else = the error to stop with. */
  public static String verdict(String version, String renderer) {
    if (version == null) return "WARN GL_VERSION not available: cannot check the OpenGL ES version";
    Matcher matcher = ES.matcher(version);
    if (!matcher.find()) return "WARN not an OpenGL ES context (" + version + "): BTA Movil is made for OpenGL ES 3.2";
    int major = Integer.parseInt(matcher.group(1)), minor = Integer.parseInt(matcher.group(2));
    if (major > 3 || (major == 3 && minor >= 2)) return null;
    return "BTA Movil needs OpenGL ES 3.2, but this device/renderer only offers " + version
        + " (GL_RENDERER: " + (renderer == null ? "unknown renderer" : renderer) + ")"
        + ". Its shaders (GLSL ES 3.20) cannot run here. Phones with OpenGL ES 3.2 (most Mali-G, Adreno 5xx and newer) can run it.";
  }
}
