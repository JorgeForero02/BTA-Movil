import jdk.internal.org.objectweb.asm.*;
import java.nio.file.*;
public class Ldc {
  public static void main(String[] args) throws Exception {
    Path file = Path.of(args[0]); String from = args[1], to = args[2];
    ClassReader reader = new ClassReader(Files.readAllBytes(file));
    ClassWriter writer = new ClassWriter(reader, 0);
    int[] count = {0};
    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
          public void visitLdcInsn(Object value) { if (from.equals(value)) { value = to; count[0]++; } super.visitLdcInsn(value); }
        };
      }
    }, 0);
    if (count[0] == 0) { System.err.println(file + ": NOTHING patched (unexpected bta.jar?)"); System.exit(1); }
    Files.write(file, writer.toByteArray());
    System.out.println(file + ": " + count[0] + " ldc replaced");
  }
}
