package net.betterthanadventure.btamobile.glesfix;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL43;

/** Replacements for the few desktop GL calls BTA makes that OpenGL ES 3.2 lacks or restricts. */
public final class GlesShim {
  private static final int GL_DEPTH_COMPONENT = 0x1902, GL_DEPTH_STENCIL = 0x84F9, GL_UNSIGNED_INT_24_8 = 0x84FA,
      GL_DEPTH24_STENCIL8 = 0x88F0, GL_DEPTH_COMPONENT16 = 0x81A5, GL_DEPTH_COMPONENT24 = 0x81A6, GL_DEPTH_COMPONENT32F = 0x8CAC,
      GL_RGBA = 0x1908, GL_RGB = 0x1907, GL_RGBA8 = 0x8058, GL_RGB8 = 0x8051,
      GL_FLOAT = 0x1406, GL_UNSIGNED_INT = 0x1405, GL_UNSIGNED_SHORT = 0x1403,
      GL_CLAMP = 0x2900, GL_CLAMP_TO_EDGE = 0x812F, GL_TEXTURE_LOD_BIAS = 0x8501, GL_GENERATE_MIPMAP = 0x8191,
      GL_TEXTURE_WRAP_S = 0x2802, GL_TEXTURE_WRAP_T = 0x2803, GL_TEXTURE_WRAP_R = 0x8072;

  private static boolean glesEnabled() { return GlesMode.enabled; }

  private static int fixInternal(int internalFormat, int type) {
    if (internalFormat == GL_DEPTH_COMPONENT) {
      if (type == GL_FLOAT) return GL_DEPTH_COMPONENT32F;
      if (type == GL_UNSIGNED_SHORT) return GL_DEPTH_COMPONENT16;
      return GL_DEPTH_COMPONENT24;
    }
    return internalFormat;
  }
  private static int toSizedFormat(int internalFormat) {
    switch (internalFormat) {
      case GL_RGBA: return GL_RGBA8;
      case GL_RGB: return GL_RGB8;
      case GL_DEPTH_COMPONENT: return GL_DEPTH_COMPONENT24;
      default: return internalFormat;
    }
  }

  public static void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
    if (glesEnabled()) {
      if (internalFormat == GL_DEPTH24_STENCIL8) { format = GL_DEPTH_STENCIL; type = GL_UNSIGNED_INT_24_8; }
      else if (internalFormat == GL_DEPTH_COMPONENT && type == GL_FLOAT) internalFormat = GL_DEPTH_COMPONENT32F;
      else if (internalFormat == GL_DEPTH_COMPONENT && type == GL_UNSIGNED_INT) internalFormat = GL_DEPTH_COMPONENT24;
      else internalFormat = fixInternal(internalFormat, type);
      border = 0;
    }
    GL41.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
  }
  public static void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, long pixels) {
    if (glesEnabled()) {
      if (internalFormat == GL_DEPTH24_STENCIL8) { format = GL_DEPTH_STENCIL; type = GL_UNSIGNED_INT_24_8; }
      else if (internalFormat == GL_DEPTH_COMPONENT && type == GL_FLOAT) internalFormat = GL_DEPTH_COMPONENT32F;
      else if (internalFormat == GL_DEPTH_COMPONENT && type == GL_UNSIGNED_INT) internalFormat = GL_DEPTH_COMPONENT24;
      else internalFormat = fixInternal(internalFormat, type);
      border = 0;
    }
    GL41.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
  }

  public static void glTexParameteri(int target, int pname, int param) {
    if (glesEnabled()) {
      if (pname == GL_TEXTURE_LOD_BIAS || pname == GL_GENERATE_MIPMAP) return;
      if ((pname == GL_TEXTURE_WRAP_S || pname == GL_TEXTURE_WRAP_T || pname == GL_TEXTURE_WRAP_R) && param == GL_CLAMP) param = GL_CLAMP_TO_EDGE;
    }
    GL41.glTexParameteri(target, pname, param);
  }

  public static void glRenderbufferStorage(int target, int internalFormat, int width, int height) {
    GL41.glRenderbufferStorage(target, glesEnabled() ? toSizedFormat(internalFormat) : internalFormat, width, height);
  }
  public static void glRenderbufferStorageMultisample(int target, int samples, int internalFormat, int width, int height) {
    GL41.glRenderbufferStorageMultisample(target, samples, glesEnabled() ? toSizedFormat(internalFormat) : internalFormat, width, height);
  }
  public static void glTexImage2DMultisample(int target, int samples, int internalFormat, int width, int height, boolean fixed) {
    if (glesEnabled()) { GL43.glTexStorage2DMultisample(target, samples, toSizedFormat(internalFormat), width, height, fixed); return; }
    GL41.glTexImage2DMultisample(target, samples, internalFormat, width, height, fixed);
  }

  public static void glClearDepth(double depth) {
    if (glesEnabled()) { GL41.glClearDepthf((float) depth); return; }
    GL41.glClearDepth(depth);
  }

  private static final int GL_BUFFER_SIZE = 0x8764, GL_READ_ONLY = 0x88B8, GL_WRITE_ONLY = 0x88B9,
      GL_MAP_READ_BIT = 0x1, GL_MAP_WRITE_BIT = 0x2;
  public static ByteBuffer glMapBuffer(int target, int access) {
    if (!glesEnabled()) return GL41.glMapBuffer(target, access);
    int size = GL41.glGetBufferParameteri(target, GL_BUFFER_SIZE);
    int bits = access == GL_READ_ONLY ? GL_MAP_READ_BIT : access == GL_WRITE_ONLY ? GL_MAP_WRITE_BIT : (GL_MAP_READ_BIT | GL_MAP_WRITE_BIT);
    return GL41.glMapBufferRange(target, 0, size, bits);
  }

  private static final int GL_READ_FRAMEBUFFER = 0x8CA8, GL_READ_FRAMEBUFFER_BINDING = 0x8CAA, GL_COLOR_ATTACHMENT0 = 0x8CE0,
      GL_TEXTURE_BINDING_2D = 0x8069, GL_TEXTURE_WIDTH = 0x1000, GL_TEXTURE_HEIGHT = 0x1001, GL_TEXTURE_2D = 0x0DE1;
  private static int readFbo = 0;
  /** GLES cannot read a texture directly: attach it to a framebuffer and read pixels. */
  public static void glGetTexImage(int target, int level, int format, int type, ByteBuffer pixels) {
    if (!glesEnabled()) { GL41.glGetTexImage(target, level, format, type, pixels); return; }
    if (target != GL_TEXTURE_2D) return;
    int texture = GL41.glGetInteger(GL_TEXTURE_BINDING_2D);
    int width = GL41.glGetTexLevelParameteri(target, level, GL_TEXTURE_WIDTH);
    int height = GL41.glGetTexLevelParameteri(target, level, GL_TEXTURE_HEIGHT);
    if (texture == 0 || width <= 0 || height <= 0) return;
    int previousReadFramebuffer = GL41.glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
    if (readFbo == 0) readFbo = GL41.glGenFramebuffers();
    GL41.glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
    GL41.glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, target, texture, level);
    GL41.glReadPixels(0, 0, width, height, format, type, pixels);
    GL41.glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, target, 0, level);
    GL41.glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer);
  }

  // Legacy immediate mode used only by a developer colormap tool.
  public static void glBegin(int mode) { if (!glesEnabled()) org.lwjgl.opengl.GL11.glBegin(mode); }
  public static void glEnd() { if (!glesEnabled()) org.lwjgl.opengl.GL11.glEnd(); }
  public static void glVertex2d(double x, double y) { if (!glesEnabled()) org.lwjgl.opengl.GL11.glVertex2d(x, y); }
  public static void glColor3d(double red, double green, double blue) { if (!glesEnabled()) org.lwjgl.opengl.GL11.glColor3d(red, green, blue); }
  public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) { if (!glesEnabled()) org.lwjgl.opengl.GL11.glOrtho(left, right, bottom, top, zNear, zFar); }
  public static void glLoadIdentity() { if (!glesEnabled()) org.lwjgl.opengl.GL11.glLoadIdentity(); }
}
