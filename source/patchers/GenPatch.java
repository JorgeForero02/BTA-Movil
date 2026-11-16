import jdk.internal.org.objectweb.asm.*;
import java.nio.file.*;
/** Phase 3 hooks: decoration budget + pregeneration tick. */
public class GenPatch {
  static final String CHUNK_PROVIDER = "net/minecraft/core/world/chunk/provider/ChunkProviderThreaded", MOBILE_WORLDGEN = "net/minecraft/core/world/chunk/provider/MobileWorldgen";
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    int[] hooks = new int[4];
    rewrite(root.resolve(CHUNK_PROVIDER + ".class"), next -> new ClassVisitor(Opcodes.ASM9, next) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        if (name.equals("decorate") && desc.equals("(Lnet/minecraft/core/world/chunk/Chunk;)V")) {
          // keep BTA's method under another name; a wrapper with try/finally is added in visitEnd
          hooks[1]++;
          return super.visitMethod(access, "decorate$bta", desc, signature, exceptions);
        }
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        if (name.equals("handleDecoration") && desc.equals("(Lnet/minecraft/core/world/chunk/Chunk;)Z")) {
          return new MethodVisitor(Opcodes.ASM9, visitor) {
            public void visitMethodInsn(int opcode, String owner, String calleeName, String calleeDesc, boolean isInterface) {
              if (opcode == Opcodes.INVOKEVIRTUAL && calleeName.equals("isChunkValid") && calleeDesc.equals("(Lnet/minecraft/core/world/pos/ChunkPosc;)Z")) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, MOBILE_WORLDGEN, "validForDecoration", "(L" + CHUNK_PROVIDER + ";Lnet/minecraft/core/world/pos/ChunkPosc;)Z", false);
                hooks[0]++; return;
              }
              super.visitMethodInsn(opcode, owner, calleeName, calleeDesc, isInterface);
            }
          };
        }
        if (name.equals("tick") && desc.equals("()Z")) {
          return new MethodVisitor(Opcodes.ASM9, visitor) {
            public void visitCode() { super.visitCode(); super.visitVarInsn(Opcodes.ALOAD, 0);
              super.visitMethodInsn(Opcodes.INVOKESTATIC, MOBILE_WORLDGEN, "onTick", "(L" + CHUNK_PROVIDER + ";)V", false); hooks[2]++; }
          };
        }
        return visitor;
      }
      public void visitEnd() {
        String chunkDesc = "Lnet/minecraft/core/world/chunk/Chunk;";
        MethodVisitor generated = super.visitMethod(Opcodes.ACC_PUBLIC, "decorate", "(" + chunkDesc + ")V", null, null);
        generated.visitCode();
        Label start = new Label(), end = new Label(), handler = new Label();
        generated.visitTryCatchBlock(start, end, handler, null);
        generated.visitVarInsn(Opcodes.ALOAD, 1);
        generated.visitMethodInsn(Opcodes.INVOKESTATIC, MOBILE_WORLDGEN, "onDecorate", "(" + chunkDesc + ")V", false);
        generated.visitLabel(start);
        generated.visitVarInsn(Opcodes.ALOAD, 0);
        generated.visitVarInsn(Opcodes.ALOAD, 1);
        generated.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CHUNK_PROVIDER, "decorate$bta", "(" + chunkDesc + ")V", false);
        generated.visitLabel(end);
        generated.visitMethodInsn(Opcodes.INVOKESTATIC, MOBILE_WORLDGEN, "onDecorateEnd", "()V", false);
        generated.visitInsn(Opcodes.RETURN);
        generated.visitLabel(handler);
        generated.visitFrame(Opcodes.F_FULL, 2, new Object[]{CHUNK_PROVIDER, "net/minecraft/core/world/chunk/Chunk"}, 1, new Object[]{"java/lang/Throwable"});
        generated.visitVarInsn(Opcodes.ASTORE, 2);
        generated.visitMethodInsn(Opcodes.INVOKESTATIC, MOBILE_WORLDGEN, "onDecorateEnd", "()V", false);
        generated.visitVarInsn(Opcodes.ALOAD, 2);
        generated.visitInsn(Opcodes.ATHROW);
        generated.visitMaxs(0, 0);
        generated.visitEnd();
        super.visitEnd();
      }
    });
    System.out.println("validForDecoration=" + hooks[0] + " onDecorate=" + hooks[1] + " onTick=" + hooks[2]);
    if (hooks[0] != 1 || hooks[1] != 1 || hooks[2] != 1) throw new IllegalStateException("unexpected counts");
  }
  interface VisitorWrapper { ClassVisitor wrap(ClassVisitor next); }
  static void rewrite(Path file, VisitorWrapper wrapper) throws Exception {
    ClassReader reader = new ClassReader(Files.readAllBytes(file));
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    reader.accept(wrapper.wrap(writer), 0);
    Files.write(file, writer.toByteArray());
  }
}
