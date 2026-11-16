package net.betterthanadventure.btamobile.meshfix;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL41;

/**
 * Compact terrain vertex format. BTA writes each terrain vertex as 28 bytes:
 * position 3 x float, colour 3 x ubyte, normal 3 x byte, UV 2 x float, shade + light 2 x ubyte.
 * Compact it is 24 bytes (-14% memory, upload and GPU bandwidth):
 * <pre>
 *  0  position x, y, z  3 x float   UNCHANGED (bit for bit)
 * 12  colour            3 x ubyte   unchanged
 * 15  normal            3 x byte    unchanged
 * 18  UV                2 x ushort  u * 32768 (atlas grid k / 2^n, up to 32768 px, is exact), read as a
 *                                   normalized ushort (UNORM: supported everywhere, also through
 *                                   Vulkan/ANGLE layers) and scaled back by 65535/32768 in the shader
 * 22  shade, light      2 x ubyte   unchanged
 * </pre>
 * Positions are deliberately NOT quantized: BTA separates coplanar faces by tiny offsets (fluid sides
 * 1.0E-4 block, fluid bottom 0.001, JSON models 0.02-0.05 px) to avoid z-fighting, and any fixed-point
 * position format below ~1/16384 block would erase them.
 *
 * Wiring (source/patchers/PackPatch.java + Redirect): the U and V putFloat calls of
 * TessellatorTerrainImpl.addVertex -> putUV, TerrainVertexConfig.getVertexSize -> size(),
 * its glVertexAttribPointer calls -> {@link #glVertexAttribPointer}, and Shader.compile's
 * glShaderSource -> {@link #glShaderSource}, which makes the terrain vertex shader scale the UV.
 */
public final class VertexPack {
  /** Decided once, the first time terrain is set up (option "Compact Vertices", restart to change). */
  public static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("bta.packedVertices"))
      && ("true".equalsIgnoreCase(System.getProperty("bta.packedVertices")) || OptionsSafe.get("packedVertices"));
  static { System.out.println("[BTA-pack] terrain vertices: " + (ON ? "compact (24 bytes)" : "original (28 bytes)")); }
  public static final int SIZE = 24;
  public static final float UV_SCALE = 32768f;
  /** UVs that did not fit (outside 0..2) and were clamped (should stay 0). */
  public static final AtomicLong clampedUV = new AtomicLong();
  private static volatile boolean warnedUV;

  // ------------------------------------------------------------------ vertex writes (any thread)

  public static short encodeUV(float u) {
    int scaled = Math.round(u * UV_SCALE);
    if (scaled < 0 || scaled > 0xFFFF) {
      clampedUV.incrementAndGet();
      if (!warnedUV) { warnedUV = true; System.out.println("[BTA-pack] texture coordinate out of 0..2: " + u + " (clamped; please report)"); }
      scaled = scaled < 0 ? 0 : 0xFFFF;
    }
    return (short) scaled;
  }

  public static float decodeUV(short packed) { return (packed & 0xFFFF) / UV_SCALE; }

  /** Replaces putFloat for U and V. */
  public static ByteBuffer putUV(ByteBuffer buffer, float u) {
    return ON ? buffer.putShort(encodeUV(u)) : buffer.putFloat(u);
  }

  /** Wraps TerrainVertexConfig.getVertexSize's result. */
  public static int size(int original) { return ON ? SIZE : original; }

  // ------------------------------------------------------------------ GL (main thread)

  private static final int GL_BYTE = 0x1400, GL_UNSIGNED_BYTE = 0x1401, GL_UNSIGNED_SHORT = 0x1403, GL_FLOAT = 0x1406;

  /** Replaces TerrainVertexConfig.enable's attribute setup (attribute index decides the packed layout). */
  public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
    if (!ON) { GL41.glVertexAttribPointer(index, size, type, normalized, stride, pointer); return; }
    switch (index) {
      case 0: GL41.glVertexAttribPointer(0, 3, GL_FLOAT, false, SIZE, 0L); break;           // position (unchanged floats)
      case 1: GL41.glVertexAttribPointer(1, 3, GL_UNSIGNED_BYTE, true, SIZE, 12L); break;   // colour
      case 2: GL41.glVertexAttribPointer(2, 3, GL_BYTE, true, SIZE, 15L); break;            // normal
      case 3: GL41.glVertexAttribPointer(3, 2, GL_UNSIGNED_SHORT, true, SIZE, 18L); break;  // UV (UNORM, rescaled in the shader)
      case 4: GL41.glVertexAttribPointer(4, 2, GL_UNSIGNED_BYTE, false, SIZE, 22L); break;  // shade, light
      default: GL41.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }
  }

  private static final Pattern TERRAIN_UV = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*3\\s*\\)\\s*in\\s+vec2\\s+aUV\\s*;");

  /** Replaces glShaderSource in Shader.compile: the terrain vertex shader gets the UV scale. */
  public static void glShaderSource(int shader, CharSequence source) {
    net.betterthanadventure.btamobile.glesfix.GlesCheck.once(); // first shader: the context exists, check the ES version
    GL41.glShaderSource(shader, filter(source));
  }

  /** Pure function (tested): only the terrain vertex shader (the one with aLightShade) is changed. */
  public static CharSequence filter(CharSequence source) {
    if (!ON || source == null) return source;
    String text = source.toString();
    if (!text.contains("aLightShade")) return source;
    Matcher matcher = TERRAIN_UV.matcher(text);
    if (!matcher.find()) {
      System.out.println("[BTA-pack] WARNING: terrain vertex shader not recognized (custom shader pack?): terrain will look wrong. Start with -Dbta.packedVertices=false");
      return source;
    }
    // UNORM gives k / 65535; * (65535 / 32768) = k / 32768 (the constant is exact in binary)
    String decode = "layout (location = 3) in vec2 aUVPacked;\n#define aUV (aUVPacked * " + (65535.0 / UV_SCALE) + ")";
    return text.substring(0, matcher.start()) + decode + text.substring(matcher.end());
  }
}
