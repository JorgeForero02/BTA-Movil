import jdk.internal.org.objectweb.asm.*;
import java.nio.file.*;
/** Compact terrain vertices (VertexPack):
 *  1) TessellatorTerrainImpl.addVertex: of its 5 ByteBuffer.putFloat calls (x, y, z, u, v, in that order)
 *     the last two (u, v) go to VertexPack.putUV; positions stay floats;
 *  2) TerrainVertexConfig.getVertexSize: the returned value goes through VertexPack.size(int);
 *  3) TerrainVertexConfig.enable: the offset sum its assert compares (28) goes through VertexPack.size too.
 *  (The attribute pointers and the shader source are redirected with the generic Redirect patcher.)
 *  Fails unless exactly 5 putFloat calls and 1 return were patched. Args: <jar dir> */
public class PackPatch {
  static final String VERTEX_PACK = "net/betterthanadventure/btamobile/meshfix/VertexPack";
  public static void main(String[] args) throws Exception {
    Path dir = Path.of(args[0], "net/minecraft/client/render/tessellator");
    // 1) addVertex
    Path vertexFile = dir.resolve("TessellatorTerrainImpl.class");
    ClassReader reader = new ClassReader(Files.readAllBytes(vertexFile));
    ClassWriter writer = new ClassWriter(reader, 0);
    int[] puts = {0};
    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        if (!name.equals("addVertex") || !desc.equals("(DDD)V")) return visitor;
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          public void visitMethodInsn(int opcode, String owner, String calleeName, String calleeDesc, boolean isInterface) {
            if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/nio/ByteBuffer") && calleeName.equals("putFloat") && calleeDesc.equals("(F)Ljava/nio/ByteBuffer;")) {
              int i = puts[0]++;
              if (i >= 3) { super.visitMethodInsn(Opcodes.INVOKESTATIC, VERTEX_PACK, "putUV", "(Ljava/nio/ByteBuffer;F)Ljava/nio/ByteBuffer;", false); return; }
            }
            super.visitMethodInsn(opcode, owner, calleeName, calleeDesc, isInterface);
          }
        };
      }
    }, 0);
    if (puts[0] != 5) { System.err.println("PackPatch: expected 5 putFloat in addVertex, found " + puts[0]); System.exit(1); }
    Files.write(vertexFile, writer.toByteArray());
    // 2) getVertexSize
    Path configFile = dir.resolve("TessellatorTerrainImpl$TerrainVertexConfig.class");
    reader = new ClassReader(Files.readAllBytes(configFile));
    writer = new ClassWriter(reader, 0);
    int[] rets = {0}, asserts = {0};
    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        if (name.equals("enable") && desc.equals("(II)V")) {
          // its "assert offset == getVertexSize()" adds up the original 28 bytes: map that sum through
          // VertexPack.size too, so the assert still checks (and passes) with -ea in both formats
          return new MethodVisitor(Opcodes.ASM9, visitor) {
            boolean inAssert;
            public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
              if (opcode == Opcodes.GETSTATIC && fieldName.equals("$assertionsDisabled")) inAssert = true;
              super.visitFieldInsn(opcode, owner, fieldName, fieldDesc);
            }
            public void visitVarInsn(int opcode, int var) {
              super.visitVarInsn(opcode, var);
              if (inAssert && opcode == Opcodes.ILOAD && var == 4) { super.visitMethodInsn(Opcodes.INVOKESTATIC, VERTEX_PACK, "size", "(I)I", false); inAssert = false; asserts[0]++; }
            }
          };
        }
        if (!name.equals("getVertexSize") || !desc.equals("()I")) return visitor;
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          public void visitInsn(int opcode) {
            if (opcode == Opcodes.IRETURN) { super.visitMethodInsn(Opcodes.INVOKESTATIC, VERTEX_PACK, "size", "(I)I", false); rets[0]++; }
            super.visitInsn(opcode);
          }
        };
      }
    }, 0);
    if (rets[0] != 1) { System.err.println("PackPatch: expected 1 return in getVertexSize, found " + rets[0]); System.exit(1); }
    if (asserts[0] != 1) { System.err.println("PackPatch: expected 1 size assert in enable, found " + asserts[0]); System.exit(1); }
    Files.write(configFile, writer.toByteArray());
    System.out.println("PackPatch: addVertex puts=" + puts[0] + " getVertexSize returns=" + rets[0] + " enable asserts=" + asserts[0]);
  }
}
