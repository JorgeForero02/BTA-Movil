import jdk.internal.org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
/** Wires cave/entity culling and the video options into BTA. */
public class CullPatch {
  static final String RENDER_GLOBAL = "net/minecraft/client/render/RenderGlobal";
  static final Set<String> WIDEN = Set.of("chunkRenderers", "xChunks", "yChunks", "zChunks", "occludedChunks", "culledEntities", "renderedEntities");
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    int[] counts = new int[6];
    // 1) RenderGlobal: widen fields, redirect entity / tile entity rendering in renderEntities
    patch(root, RENDER_GLOBAL, (next) -> new ClassVisitor(Opcodes.ASM9, next) {
      public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
        if (WIDEN.contains(name)) { access = (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC; counts[0]++; }
        return super.visitField(access, name, desc, signature, value);
      }
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        if (!name.equals("renderEntities")) return visitor;
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          int entityRenderCalls = 0;
          public void visitMethodInsn(int opcode, String owner, String calleeName, String calleeDesc, boolean isInterface) {
            if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/client/render/EntityRendererDispatcher") && calleeName.equals("renderEntity")) {
              if (++entityRenderCalls == 2) { // second call = loaded entities loop (first is weather effects / lightning)
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/render/CullHooks", "renderEntity",
                    "(Lnet/minecraft/client/render/EntityRendererDispatcher;Lnet/minecraft/client/render/tessellator/TessellatorGeneral;Lnet/minecraft/core/entity/Entity;F)V", false);
                counts[1]++; return;
              }
            }
            if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/client/render/TileEntityRenderDispatcher") && calleeName.equals("renderTileEntity")
                && calleeDesc.equals("(Lnet/minecraft/client/render/tessellator/TessellatorGeneral;Lnet/minecraft/client/render/camera/ICamera;Lnet/minecraft/core/world/World;Lnet/minecraft/core/block/entity/TileEntity;F)V")) {
              super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/render/CullHooks", "renderTileEntity",
                  "(Lnet/minecraft/client/render/TileEntityRenderDispatcher;Lnet/minecraft/client/render/tessellator/TessellatorGeneral;Lnet/minecraft/client/render/camera/ICamera;Lnet/minecraft/core/world/World;Lnet/minecraft/core/block/entity/TileEntity;F)V", false);
              counts[2]++; return;
            }
            super.visitMethodInsn(opcode, owner, calleeName, calleeDesc, isInterface);
          }
        };
      }
    });
    // 2) WorldRenderer: renderGlobal.cull(frustum, pt) -> CaveCuller.cull(renderGlobal, frustum, pt)
    patch(root, "net/minecraft/client/render/WorldRenderer", (next) -> new ClassVisitor(Opcodes.ASM9, next) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
          public void visitMethodInsn(int opcode, String owner, String calleeName, String calleeDesc, boolean isInterface) {
            if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(RENDER_GLOBAL) && calleeName.equals("cull") && calleeDesc.equals("(Lnet/minecraft/client/render/culling/CameraFrustum;F)V")) {
              super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/render/terrain/CaveCuller", "cull",
                  "(Lnet/minecraft/client/render/RenderGlobal;Lnet/minecraft/client/render/culling/CameraFrustum;F)V", false);
              counts[3]++; return;
            }
            super.visitMethodInsn(opcode, owner, calleeName, calleeDesc, isInterface);
          }
        };
      }
    });
    // 3) OptionsPages.init(): right after VIDEO is stored, add our category
    patch(root, "net/minecraft/client/gui/options/data/OptionsPages", (next) -> new ClassVisitor(Opcodes.ASM9, next) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        if (!name.equals("init") || !desc.equals("()V")) return visitor;
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
            super.visitFieldInsn(opcode, owner, fieldName, fieldDesc);
            if (opcode == Opcodes.PUTSTATIC && fieldName.equals("VIDEO")) {
              super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/betterthanadventure/btamobile/meshfix/MobileOptions", "addVideoCategory", "()V", false);
              counts[4]++;
            }
          }
        };
      }
    });
    System.out.println("widened=" + counts[0] + " entity=" + counts[1] + " tile=" + counts[2] + " cull=" + counts[3] + " menu=" + counts[4]);
    if (counts[0] != 7 || counts[1] != 1 || counts[2] != 1 || counts[3] != 1 || counts[4] != 1) throw new IllegalStateException("unexpected patch counts");
  }
  interface VisitorWrapper { ClassVisitor wrap(ClassVisitor next); }
  static void patch(Path root, String cls, VisitorWrapper wrapper) throws Exception {
    Path file = root.resolve(cls + ".class");
    ClassReader reader = new ClassReader(Files.readAllBytes(file));
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
    reader.accept(wrapper.wrap(writer), 0);
    Files.write(file, writer.toByteArray());
  }
}
