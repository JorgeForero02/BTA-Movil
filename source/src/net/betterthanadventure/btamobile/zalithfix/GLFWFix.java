package net.betterthanadventure.btamobile.zalithfix;
import java.nio.IntBuffer;
import org.lwjgl.glfw.GLFW;
/**
 * Pojav-derived launchers (Zalith, Amethyst) ship a GLFW stub whose IntBuffer variants
 * advance the buffer position, and some of them are missing entirely (Amethyst lacks
 * glfwGetWindowPos(long, IntBuffer, IntBuffer)). Route through the int[] variants, which
 * exist in all of them, and write at the current position without moving it, like real LWJGL.
 */
public final class GLFWFix {
  private static void putAtPosition(IntBuffer buffer, int[] source){ if (buffer != null) buffer.put(buffer.position(), source[0]); }
  public static void glfwGetWindowSize(long window, IntBuffer x, IntBuffer y){ int[] xArray=new int[1], yArray=new int[1]; GLFW.glfwGetWindowSize(window, xArray, yArray); putAtPosition(x,xArray); putAtPosition(y,yArray); }
  public static void glfwGetFramebufferSize(long window, IntBuffer x, IntBuffer y){ int[] xArray=new int[1], yArray=new int[1]; GLFW.glfwGetFramebufferSize(window, xArray, yArray); putAtPosition(x,xArray); putAtPosition(y,yArray); }
  public static void glfwGetWindowPos(long window, IntBuffer x, IntBuffer y){ int[] xArray=new int[1], yArray=new int[1]; GLFW.glfwGetWindowPos(window, xArray, yArray); putAtPosition(x,xArray); putAtPosition(y,yArray); }
  public static void glfwGetMonitorPos(long monitor, IntBuffer x, IntBuffer y){ int[] xArray=new int[1], yArray=new int[1]; GLFW.glfwGetMonitorPos(monitor, xArray, yArray); putAtPosition(x,xArray); putAtPosition(y,yArray); }
}
