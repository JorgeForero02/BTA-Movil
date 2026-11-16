package net.betterthanadventure.btamobile.glesfix;

import org.lwjgl.opengl.GL41;

/**
 * OpenGL error log ("[BTA-GL]" lines), for reports from phones we cannot test.
 *
 * Always (all drivers): every 30 frames (-Dbta.glErrors.every=N), at the start of a frame on the render thread,
 * glGetError is read until it is clear (at most 8 flags). glGetError is the one error check every OpenGL ES driver
 * has; a flag stays set until read, so nothing is lost by not reading it every frame, and drivers that run on their
 * own thread (where glGetError waits for that thread) are not made to wait every frame. BTA's own OpenGLHelper.checkError is empty in this build, so nothing else reads the flags,
 * except the mapped-upload fallback (BufferUpload), which reports what it clears here with its own tag.
 * A flag is set once until read, so the log counts frames (or checks) with each error, not failed calls. The first
 * 20 are written with where and when they were seen, then a summary every 60 s while they keep coming. A first line
 * says which GPU, OpenGL version and mode (direct GLES or the launcher's translator) the report comes from.
 * -Dbta.glErrors=false turns it off.
 *
 * Optional, -Dbta.glDebug=true: the driver's own messages (KHR_debug) for errors and high-severity problems,
 * synchronous (right after the faulty call; a little slower, so opt-in). Only when the driver really has it
 * (OpenGL ES 3.2, desktop OpenGL 4.3 or GL_KHR_debug in a desktop driver's extensions, and the functions loaded):
 * on Android a function pointer alone proves nothing, the loader returns stubs for unknown names, and ES 3.0/3.1 only
 * have them with a KHR suffix. A new GL context (the launcher recreated it) starts the log over.
 */
public final class GlErrors {
  private GlErrors() { }
  private static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("bta.glErrors"));
  private static final boolean DEBUG = "true".equalsIgnoreCase(System.getProperty("bta.glDebug"));
  private static final int MAX_LINES = 20;
  private static final int EVERY = every();
  private static final long SUMMARY_NS = 60_000_000_000L;
  private static long frames, total, sinceSummary, lastSummary, lastRead;
  private static int lines, failures;
  private static boolean announced, off, lost, broken;
  private static Object debugContext;        // the context the debug output was set up for
  private static Object callback;    // keeps the debug callback alive
  private static final java.util.concurrent.atomic.AtomicInteger DEBUG_MESSAGES = new java.util.concurrent.atomic.AtomicInteger();

  /** Start of every rendered frame (Profiler.enter(FRAME)), render thread. */
  public static void frame() {
    if (!ON || broken) return;
    frames++;
    Object context;
    try { context = org.lwjgl.opengl.GL.getCapabilities(); } catch (Throwable ignored) { return; } // no context right now: skip
    if (context != debugContext) { // first frame, or the launcher recreated the context: start over
      if (debugContext != null) log("new GL context");
      debugContext = context; off = lost = announced = false; failures = 0; lastRead = frames - EVERY; // first read: this frame
      lines = 0; sinceSummary = 0; lastSummary = 0; DEBUG_MESSAGES.set(0);
    }
    if (off || lost) return;
    if (frames - lastRead < EVERY) return;
    long from = lastRead + 1;
    boolean first = !announced;
    lastRead = frames;
    try {
      if (!announced) {
        announced = true;
        String version = GL41.glGetString(0x1F02);
        log("GPU " + GL41.glGetString(0x1F01) + " | " + version + " | " + (version != null && version.startsWith("OpenGL ES") ? "direct OpenGL ES" : "desktop OpenGL or a translator")
            + " | checked every " + EVERY + " frames");
      }
      for (int i = 0; i < 8; i++) {
        int errorCode = GL41.glGetError();
        if (errorCode == 0) break;
        record(errorCode, first ? "before frame " + frames + " (startup)" : EVERY == 1 ? "frame " + frames : "frames " + from + "-" + frames);
        if (errorCode == 0x0507) { lost = true; log("context lost: error check paused until a new context"); return; } // repeats
      }
      if (first && DEBUG) { // after the game's own errors were read; its failure never stops the error check
        try { enableDebug(); } catch (Throwable failure) { log("KHR_debug could not be enabled: " + failure); }
      }
      long now = System.nanoTime();
      if (lastSummary == 0) lastSummary = now;
      if (now - lastSummary >= SUMMARY_NS) {
        if (sinceSummary > 0) log(sinceSummary + " more errors seen in the last minute (" + total + " in total)");
        sinceSummary = 0;
        lastSummary = now;
      }
      failures = 0;
    } catch (LinkageError | NullPointerException failure) { // NPE: LWJGL's check for a function the driver did not give
      broken = true; // no glGetError at all: never try again
      log("error check unavailable: " + failure);
    } catch (Throwable failure) {
      if (++failures >= 3) { off = true; log("error check stopped after repeated failures: " + failure); }
    }
  }

  /** An error flag read by our code ({@code where}: what was being done). Render thread. */
  public static void record(int errorCode, String where) {
    if (!ON || errorCode == 0) return;
    total++;
    if (lines >= MAX_LINES) sinceSummary++;
    else {
      lines++;
      log("error 0x" + Integer.toHexString(errorCode) + " " + name(errorCode) + " (" + where + ")" + (lines == MAX_LINES ? "; more only in a summary every minute" : ""));
    }
  }

  private static boolean hasDebug() {
    org.lwjgl.opengl.GLCapabilities capabilities = org.lwjgl.opengl.GL.getCapabilities();
    if (capabilities.glDebugMessageCallback == 0 || capabilities.glDebugMessageControl == 0) return false; // not loaded: calling would crash
    String version = GL41.glGetString(0x1F02); // GL_VERSION
    if (version == null) return false;
    if (version.startsWith("OpenGL ES")) return version.startsWith("OpenGL ES 3.2"); // 3.0/3.1: only ...KHR names, ours are stubs
    int major = 0, minor = 0;
    try {
      String[] parts = version.trim().split("[ .]");
      major = Integer.parseInt(parts[0]);
      minor = Integer.parseInt(parts[1]);
    } catch (RuntimeException ignored) { }
    // a translator (Android) may say 4.x and hand out stubs: there, only with the extension listed
    boolean android = new java.io.File("/system/build.prop").exists();
    if (!android && (major > 4 || (major == 4 && minor >= 3))) return true;
    if (major < 3) { String extensions = GL41.glGetString(0x1F03); return extensions != null && extensions.contains("GL_KHR_debug"); } // GL 2.x
    int extensionCount = GL41.glGetInteger(0x821D); // GL_NUM_EXTENSIONS
    for (int i = 0; i < extensionCount; i++) if ("GL_KHR_debug".equals(GL41.glGetStringi(0x1F03, i))) return true;
    return false;
  }

  private static void enableDebug() {
    try {
      drain("before KHR_debug");
      if (!hasDebug()) { log("KHR_debug not available on this driver"); clear(); return; }
      org.lwjgl.opengl.GLDebugMessageCallback debugCallback = org.lwjgl.opengl.GLDebugMessageCallback.create((source, type, id, severity, length, message, user) -> {
        try {
          if (type != 0x824C && severity != 0x9146) return; // errors and high severity only, whatever the driver's filter
          if (DEBUG_MESSAGES.get() >= 50) return;
          int count = DEBUG_MESSAGES.incrementAndGet();
          if (count > 50) return;
          String text = length > 0 ? org.lwjgl.opengl.GLDebugMessageCallback.getMessage(length, message) : org.lwjgl.system.MemoryUtil.memUTF8Safe(message);
          log("driver: " + text + " (type 0x" + Integer.toHexString(type) + ", severity 0x" + Integer.toHexString(severity) + ")"
              + (count == 50 ? "; no more driver messages" : ""));
        } catch (Throwable ignored) { } // nothing may leave a native callback
      });
      callback = debugCallback; // a previous context's callback is not freed: its driver may still call it (a small, one-off leak)
      GL41.glEnable(0x92E0); // GL_DEBUG_OUTPUT
      GL41.glEnable(0x8242); // GL_DEBUG_OUTPUT_SYNCHRONOUS
      org.lwjgl.opengl.GL43.glDebugMessageControl(0x1100, 0x1100, 0x1100, (java.nio.IntBuffer) null, false); // all off
      org.lwjgl.opengl.GL43.glDebugMessageControl(0x1100, 0x824C, 0x1100, (java.nio.IntBuffer) null, true);  // errors
      org.lwjgl.opengl.GL43.glDebugMessageControl(0x1100, 0x1100, 0x9146, (java.nio.IntBuffer) null, true);  // high severity
      org.lwjgl.opengl.GL43.glDebugMessageCallback(debugCallback, 0L);
      boolean synchronous = GL41.glIsEnabled(0x8242);
      drain("enabling KHR_debug");
      log("KHR_debug on (errors and high severity" + (synchronous ? ", synchronous" : ", asynchronous: messages may come from another thread")
          + "; some drivers stay silent outside a debug context)");
    } catch (Throwable failure) {
      clear();
      log("KHR_debug could not be enabled: " + failure);
    }
  }

  private static void drain(String where) {
    try { for (int i = 0; i < 8; i++) { int errorCode = GL41.glGetError(); if (errorCode == 0) break; record(errorCode, where); } } catch (Throwable ignored) { }
  }

  /** Our own checks may leave a flag (an unknown enum on an old driver): not the game's, so not logged. */
  private static void clear() {
    try { for (int i = 0; i < 8 && GL41.glGetError() != 0; i++) { } } catch (Throwable ignored) { }
  }

  private static int every() {
    try { return Math.max(1, Integer.parseInt(System.getProperty("bta.glErrors.every", "30").trim())); } catch (RuntimeException ignored) { return 30; }
  }

  static String name(int errorCode) {
    switch (errorCode) {
      case 0x0500: return "GL_INVALID_ENUM";
      case 0x0501: return "GL_INVALID_VALUE";
      case 0x0502: return "GL_INVALID_OPERATION";
      case 0x0503: return "GL_STACK_OVERFLOW";
      case 0x0504: return "GL_STACK_UNDERFLOW";
      case 0x0505: return "GL_OUT_OF_MEMORY";
      case 0x0506: return "GL_INVALID_FRAMEBUFFER_OPERATION";
      case 0x0507: return "GL_CONTEXT_LOST";
      default: return "?";
    }
  }

  private static void log(String message) { System.out.println("[BTA-GL] " + message); }
}
