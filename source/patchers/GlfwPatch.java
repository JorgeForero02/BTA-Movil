import jdk.internal.org.objectweb.asm.*;
import java.nio.file.*;
import java.util.Set;
/** Redirects GLFW size/position queries to GLFWFix. Usage: {@code GlfwPatch <expected>:<file>...}; each file must yield exactly that many calls. */
public class GlfwPatch {
  static final Set<String> NAMES = Set.of("glfwGetWindowSize","glfwGetFramebufferSize","glfwGetWindowPos","glfwGetMonitorPos");
  public static void main(String[] args) throws Exception {
    boolean ok = true;
    for (String arg : args) {
      int sep = arg.indexOf(':');
      if (sep < 1) { System.err.println("GlfwPatch: argument '" + arg + "', expected <expected>:<file>"); System.exit(2); }
      int expected = Integer.parseInt(arg.substring(0, sep));
      String file = arg.substring(sep + 1);
      byte[] classBytes = Files.readAllBytes(Path.of(file));
      ClassReader reader = new ClassReader(classBytes);
      ClassWriter writer = new ClassWriter(reader, 0);
      int[] count = {0};
      reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
          return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
            public void visitMethodInsn(int opcode, String owner, String calleeName, String calleeDesc, boolean isInterface) {
              if (opcode == Opcodes.INVOKESTATIC && owner.equals("org/lwjgl/glfw/GLFW") && NAMES.contains(calleeName)
                  && calleeDesc.equals("(JLjava/nio/IntBuffer;Ljava/nio/IntBuffer;)V")) {
                owner = "net/betterthanadventure/btamobile/zalithfix/GLFWFix"; count[0]++;
              }
              super.visitMethodInsn(opcode, owner, calleeName, calleeDesc, isInterface);
            }
          };
        }
      }, 0);
      Files.write(Path.of(file), writer.toByteArray());
      System.out.println(file + ": " + count[0] + " calls patched");
      if (count[0] != expected) {
        System.err.println("GlfwPatch: " + Path.of(file).getFileName() + " = " + count[0] + ", expected " + expected);
        ok = false;
      }
    }
    if (!ok) System.exit(1);
  }
}
