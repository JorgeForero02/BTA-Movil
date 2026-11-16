package net.betterthanadventure.btamobile.glesfix;

public class GlesCheckTest {
  public static void main(String[] a) {
    String low = GlesCheck.verdict("OpenGL ES 3.1 v1.r20", "Mali-T860");
    boolean ok = GlesCheck.verdict("OpenGL ES 3.2 v1.r44p1-01eac0.999bbcba36b4ca04774e2b05d5802dc0", "Mali-G57") == null
        && GlesCheck.verdict("OpenGL ES 3.2 V@0502.0 (GIT@...)", "Adreno (TM) 610") == null
        && GlesCheck.verdict("OpenGL ES 4.0 future", null) == null
        && !low.startsWith("WARN") && low.contains("Mali-T860") && low.contains("OpenGL ES 3.1 v1.r20")
        && !GlesCheck.verdict("OpenGL ES 3.0 build 1.13@5776728", "PowerVR Rogue GE8320").startsWith("WARN")
        && GlesCheck.verdict("OpenGL ES 3.0 build 1.13@5776728", "PowerVR Rogue GE8320").contains("PowerVR Rogue GE8320")
        && GlesCheck.verdict("OpenGL ES 3.0 build 1.13@5776728", null).contains("unknown renderer")
        && GlesCheck.verdict("4.1 Metal - 83.1", "Apple M1").startsWith("WARN")
        && GlesCheck.verdict(null, null).startsWith("WARN");
    System.out.println("GLES check: " + (ok ? "OK (3.2+ continues, 3.0/3.1 stops with a clear message naming the GL_RENDERER, others only warn)" : "FAIL"));
    if (!ok) System.exit(1);
  }
}
