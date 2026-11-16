import jdk.internal.org.objectweb.asm.*;
import java.nio.file.*;
/** Player block actions -> PlayerEdit.at(pos) (sections they change are rebuilt in the same frame):
 *  at the start of PlayerController.destroyBlock(TilePosc, Side), useOrPlaceItemStackOnTile(...) and
 *  placeItemStackOnTile(...) of the base class (PlayerControllerSP/MP call these through super).
 *  The VertexBuffer hooks for fenced uploads are done here too:
 *  VertexBuffer.removeEntry(entry) starts with "if (BufferUpload.deferFree(this, entry)) return;".
 *  Fails unless exactly 3 + 1 hooks were added. Args: <jar dir> */
public class EditPatch {
  static final String PLAYER_CONTROLLER = "net/minecraft/client/player/controller/PlayerController";
  static final String POS = "Lnet/minecraft/core/world/pos/TilePosc;";
  public static void main(String[] args) throws Exception {
    int[] playerHooks = {0};
    Path controllerFile = Path.of(args[0], PLAYER_CONTROLLER + ".class");
    ClassReader reader = new ClassReader(Files.readAllBytes(controllerFile));
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        final int slot;
        if (name.equals("destroyBlock") && desc.equals("(" + POS + "Lnet/minecraft/core/util/helper/Side;)Z")) slot = 1;
        else if ((name.equals("useOrPlaceItemStackOnTile") || name.equals("placeItemStackOnTile"))
            && desc.equals("(Lnet/minecraft/core/entity/player/Player;Lnet/minecraft/core/world/World;Lnet/minecraft/core/item/ItemStack;" + POS + "Lnet/minecraft/core/util/helper/Side;DD)Z")) slot = 4;
        else return visitor;
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, slot);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/betterthanadventure/btamobile/meshfix/PlayerEdit", "at", "(" + POS + ")V", false);
            playerHooks[0]++;
          }
        };
      }
    }, 0);
    if (playerHooks[0] != 3) { System.err.println("EditPatch: expected 3 PlayerController hooks, found " + playerHooks[0]); System.exit(1); }
    Files.write(controllerFile, writer.toByteArray());

    int[] removeHooks = {0};
    String VERTEX_BUFFER = "net/minecraft/client/render/terrain/VertexBuffer";
    Path bufferFile = Path.of(args[0], VERTEX_BUFFER + ".class");
    reader = new ClassReader(Files.readAllBytes(bufferFile));
    writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        if (!name.equals("removeEntry") || !desc.equals("(L" + VERTEX_BUFFER + "$Entry;)V")) return visitor;
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          public void visitCode() {
            super.visitCode();
            // if (BufferUpload.deferFree(this, entry)) return;   (space given back once its fence completes)
            Label orig = new Label();
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ALOAD, 1);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/betterthanadventure/btamobile/zalithfix/BufferUpload", "deferFree", "(L" + VERTEX_BUFFER + ";L" + VERTEX_BUFFER + "$Entry;)Z", false);
            super.visitJumpInsn(Opcodes.IFEQ, orig);
            super.visitInsn(Opcodes.RETURN);
            super.visitLabel(orig);
            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            removeHooks[0]++;
          }
        };
      }
    }, 0);
    if (removeHooks[0] != 1) { System.err.println("EditPatch: expected 1 VertexBuffer.removeEntry hook, found " + removeHooks[0]); System.exit(1); }
    Files.write(bufferFile, writer.toByteArray());
    System.out.println("EditPatch: player action hooks=" + playerHooks[0] + " removeEntry hooks=" + removeHooks[0]);
  }
}
