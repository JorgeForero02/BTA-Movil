import jdk.internal.org.objectweb.asm.*;
import java.nio.file.*;
/** RenderGlobal hooks for async meshing. */
public class RgPatch {
  static final String RENDER_GLOBAL = "net/minecraft/client/render/RenderGlobal", CHUNK_RENDERER = "net/minecraft/client/render/terrain/ChunkRenderer",
      ASYNC_RENDERER = "net/minecraft/client/render/terrain/AsyncChunkRenderer";
  public static void main(String[] args) throws Exception {
    Path file = Path.of(args[0]);
    ClassReader reader = new ClassReader(Files.readAllBytes(file));
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
    int[] hookCounts = new int[4];
    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        boolean isUpdate = name.equals("updateDirtyChunks") && desc.equals("(Lnet/minecraft/client/render/camera/ICamera;)Z");
        boolean isMark = (name.equals("setDirty") && desc.equals("(IIIIII)V")) || (name.equals("allChanged") && desc.equals("(ZZ)V"));
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          public void visitCode() {
            super.visitCode();
            if (isUpdate) {
              super.visitVarInsn(Opcodes.ALOAD, 0);
              super.visitFieldInsn(Opcodes.GETFIELD, RENDER_GLOBAL, "dirtyChunks", "Ljava/util/List;");
              super.visitMethodInsn(Opcodes.INVOKESTATIC, ASYNC_RENDERER, "drainCompleted", "(Ljava/util/List;)V", false);
              hookCounts[0]++;
            }
          }
          public void visitIntInsn(int opcode, int operand) {
            if (isUpdate && opcode == Opcodes.SIPUSH && operand == 2048) { // near threshold: 576 with async meshing, BTA's 2048 without
              super.visitMethodInsn(Opcodes.INVOKESTATIC, ASYNC_RENDERER, "nearThreshold", "()I", false); hookCounts[3]++; return;
            }
            super.visitIntInsn(opcode, operand);
          }
          public void visitMethodInsn(int opcode, String owner, String calleeName, String calleeDesc, boolean isInterface) {
            if (isUpdate && opcode == Opcodes.INVOKEVIRTUAL && owner.equals(CHUNK_RENDERER) && calleeName.equals("distanceToCameraSquared")) {
              super.visitMethodInsn(Opcodes.INVOKESTATIC, ASYNC_RENDERER, "selectDistance", "(L" + CHUNK_RENDERER + ";Lnet/minecraft/client/render/camera/ICamera;)F", false); hookCounts[1]++; return;
            }
            if (isMark && opcode == Opcodes.INVOKEVIRTUAL && owner.equals(CHUNK_RENDERER) && calleeName.equals("isDirty")) {
              super.visitMethodInsn(Opcodes.INVOKESTATIC, ASYNC_RENDERER, "markIfMeshing", "(L" + CHUNK_RENDERER + ";)Z", false); hookCounts[2]++; return;
            }
            super.visitMethodInsn(opcode, owner, calleeName, calleeDesc, isInterface);
          }
        };
      }
    }, 0);
    System.out.println("drain=" + hookCounts[0] + " selectDistance=" + hookCounts[1] + " markIfMeshing=" + hookCounts[2] + " nearThreshold=" + hookCounts[3]);
    if (hookCounts[0] != 1 || hookCounts[1] != 1 || hookCounts[2] != 2 || hookCounts[3] != 1) throw new IllegalStateException("unexpected hook counts");
    Files.write(file, writer.toByteArray());
  }
}
