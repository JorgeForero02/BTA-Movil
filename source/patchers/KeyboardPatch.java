import jdk.internal.org.objectweb.asm.*;
import java.nio.file.*;
/** On-screen keyboard (ScreenKeyboard) edits text fields without calling keyPressed. Makes the two screens that only
 *  read their fields there read them every tick:
 *  ScreenCreateWorld.buttonClicked(ButtonElement) starts with "if (KeyboardSync.ON) this.syncConfig();" and tick() with
 *  the same but only if KeyboardSync.differs(name field, seed field, worldConfiguration),
 *  ScreenAddServer.tick() ends with buttonSave.enabled = KeyboardSync.saveEnabled(buttonSave.enabled, ipTextField.getText()).
 *  Fails unless exactly 1 + 1 + 1 insertions were made (and ScreenAddServer.tick has exactly one RETURN). Args: <jar dir> */
public class KeyboardPatch {
  static final String GUI = "net/minecraft/client/gui/";
  static final String SYNC = GUI + "KeyboardSync";
  static final String CREATE_WORLD = GUI + "ScreenCreateWorld";
  static final String ADD_SERVER = GUI + "ScreenAddServer";
  static final String BUTTON = GUI + "ButtonElement";
  static final String TEXT_FIELD = GUI + "TextFieldElement";
  static final String WORLD_CONFIG = "net/minecraft/core/world/settings/WorldConfiguration";

  static void fail(String what, int count) { System.err.println("KeyboardPatch: " + what + " = " + count + ", expected 1"); System.exit(1); }

  public static void main(String[] args) throws Exception {
    int[] hooks = {0, 0, 0, 0}; // create tick, create buttonClicked, add-server tick, RETURNs in add-server tick
    Path worldFile = Path.of(args[0], CREATE_WORLD + ".class");
    ClassReader reader = new ClassReader(Files.readAllBytes(worldFile));
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        final int slot;
        if (name.equals("tick") && desc.equals("()V")) slot = 0;
        else if (name.equals("buttonClicked") && desc.equals("(L" + BUTTON + ";)V")) slot = 1;
        else return visitor;
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          public void visitCode() {
            super.visitCode();
            Label skip = new Label();
            super.visitFieldInsn(Opcodes.GETSTATIC, SYNC, "ON", "Z");
            super.visitJumpInsn(Opcodes.IFEQ, skip);
            if (slot == 0) { // tick: only when a field's text differs from the configuration
              super.visitVarInsn(Opcodes.ALOAD, 0);
              super.visitFieldInsn(Opcodes.GETFIELD, CREATE_WORLD, "textboxWorldName", "L" + TEXT_FIELD + ";");
              super.visitVarInsn(Opcodes.ALOAD, 0);
              super.visitFieldInsn(Opcodes.GETFIELD, CREATE_WORLD, "textboxSeed", "L" + TEXT_FIELD + ";");
              super.visitVarInsn(Opcodes.ALOAD, 0);
              super.visitFieldInsn(Opcodes.GETFIELD, CREATE_WORLD, "worldConfiguration", "L" + WORLD_CONFIG + ";");
              super.visitMethodInsn(Opcodes.INVOKESTATIC, SYNC, "differs", "(L" + TEXT_FIELD + ";L" + TEXT_FIELD + ";L" + WORLD_CONFIG + ";)Z", false);
              super.visitJumpInsn(Opcodes.IFEQ, skip);
            }
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CREATE_WORLD, "syncConfig", "()V", false);
            super.visitLabel(skip);
            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            hooks[slot]++;
          }
        };
      }
    }, 0);
    if (hooks[0] != 1) fail("ScreenCreateWorld.tick hooks", hooks[0]);
    if (hooks[1] != 1) fail("ScreenCreateWorld.buttonClicked hooks", hooks[1]);
    Files.write(worldFile, writer.toByteArray());

    Path serverFile = Path.of(args[0], ADD_SERVER + ".class");
    reader = new ClassReader(Files.readAllBytes(serverFile));
    writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        if (!name.equals("tick") || !desc.equals("()V")) return visitor;
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN) {
              hooks[3]++;
              super.visitVarInsn(Opcodes.ALOAD, 0);
              super.visitFieldInsn(Opcodes.GETFIELD, ADD_SERVER, "buttonSave", "L" + BUTTON + ";");
              super.visitInsn(Opcodes.DUP);
              super.visitFieldInsn(Opcodes.GETFIELD, BUTTON, "enabled", "Z");
              super.visitVarInsn(Opcodes.ALOAD, 0);
              super.visitFieldInsn(Opcodes.GETFIELD, ADD_SERVER, "ipTextField", "L" + TEXT_FIELD + ";");
              super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TEXT_FIELD, "getText", "()Ljava/lang/String;", false);
              super.visitMethodInsn(Opcodes.INVOKESTATIC, SYNC, "saveEnabled", "(ZLjava/lang/String;)Z", false);
              super.visitFieldInsn(Opcodes.PUTFIELD, BUTTON, "enabled", "Z");
              hooks[2]++;
            }
            super.visitInsn(opcode);
          }
        };
      }
    }, 0);
    if (hooks[3] != 1) fail("ScreenAddServer.tick RETURNs", hooks[3]);
    if (hooks[2] != 1) fail("ScreenAddServer.tick hooks", hooks[2]);
    Files.write(serverFile, writer.toByteArray());
    System.out.println("KeyboardPatch: createWorld tick=" + hooks[0] + " buttonClicked=" + hooks[1] + " addServer tick=" + hooks[2]);
  }
}
