import jdk.internal.org.objectweb.asm.*;
import java.nio.file.*;
/**
 * Legacy (console) crafting hooks:
 *  1) Minecraft.displayScreen(screen): screen = LegacyCrafting.wrap(screen)  (crafting table + controller)
 *  2) console menus (ConsoleSkin): container backgrounds with the console frame, the control guide at the end
 *     of ScreenContainerAbstract.render, console X/Y in Screen.guiSpecificControllerInput, and the options
 *     category in Options > Controls.
 */
public class CraftPatch {
  static final String LEGACY_CRAFTING = "net/minecraft/client/gui/container/LegacyCrafting";
  static final String SCREEN = "Lnet/minecraft/client/gui/Screen;";
  static final String CONTROLLER_INPUT_DESC = "Lnet/minecraft/client/input/controller/ControllerInput;";
  static final String CONTAINER_ABSTRACT_DESC = "Lnet/minecraft/client/gui/container/ScreenContainerAbstract;";

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    int[] hooked = new int[1];
    rewrite(root.resolve("net/minecraft/client/Minecraft.class"), next -> new ClassVisitor(Opcodes.ASM9, next) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        if (!name.equals("displayScreen") || !desc.equals("(" + SCREEN + ")V")) return visitor;
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 1);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, LEGACY_CRAFTING, "wrap", "(" + SCREEN + ")" + SCREEN, false);
            super.visitVarInsn(Opcodes.ASTORE, 1);
            hooked[0]++;
          }
        };
      }
    });
    System.out.println("displayScreen=" + hooked[0]);
    if (hooked[0] != 1) throw new IllegalStateException("unexpected counts");
    skin(root);
  }

  static final String SKIN = "net/minecraft/client/gui/container/ConsoleSkin";

  /**
   * Console menus: in each container screen's drawGuiContainerBackgroundLayer, every
   * drawTexturedModalRect(IIIIII) goes through ConsoleSkin.blit; ScreenContainerAbstract.render ends
   * with ConsoleSkin.overlay(this) (control guide).
   */
  static void skin(Path root) throws Exception {
    String pkg = "net/minecraft/client/gui/container/";
    String[] screens = {"ScreenCrafting", "ScreenFurnace", "ScreenFurnaceBlast", "ScreenTrommel", "ScreenContainer",
        "ScreenDispenser", "ScreenActivator", "ScreenInventory"};
    int[] expect = {1, 3, 3, 3, 3, 1, 2, 1};
    for (int i = 0; i < screens.length; i++) {
      int[] blits = new int[1];
      rewrite(root.resolve(pkg + screens[i] + ".class"), next -> new ClassVisitor(Opcodes.ASM9, next) {
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
          MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
          if (!name.equals("drawGuiContainerBackgroundLayer") || !desc.equals("(F)V")) return visitor;
          return new MethodVisitor(Opcodes.ASM9, visitor) {
            public void visitMethodInsn(int opcode, String owner, String calleeName, String calleeDesc, boolean isInterface) {
              if (opcode == Opcodes.INVOKEVIRTUAL && calleeName.equals("drawTexturedModalRect") && calleeDesc.equals("(IIIIII)V")) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, SKIN, "blit", "(Lnet/minecraft/client/gui/Gui;IIIIII)V", false);
                blits[0]++;
                return;
              }
              super.visitMethodInsn(opcode, owner, calleeName, calleeDesc, isInterface);
            }
          };
        }
      });
      System.out.println(screens[i] + " blits=" + blits[0]);
      if (blits[0] != expect[i]) throw new IllegalStateException("unexpected blit count in " + screens[i]);
    }
    int[] overlays = new int[1];
    rewrite(root.resolve(pkg + "ScreenContainerAbstract.class"), next -> new ClassVisitor(Opcodes.ASM9, next) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        if (!name.equals("render") || !desc.equals("(IIF)V")) return visitor;
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN) {
              super.visitVarInsn(Opcodes.ALOAD, 0);
              super.visitMethodInsn(Opcodes.INVOKESTATIC, SKIN, "overlay", "(" + CONTAINER_ABSTRACT_DESC + ")V", false);
              overlays[0]++;
            }
            super.visitInsn(opcode);
          }
        };
      }
    });
    System.out.println("render overlay=" + overlays[0]);
    if (overlays[0] != 1) throw new IllegalStateException("unexpected render returns");

    // Screen.guiSpecificControllerInput: buttonY (right click) / buttonX (shift click) through ConsoleSkin,
    // so containers can use the console layout (X = half / one, Y = quick move)
    int[] xyReads = new int[2];
    String controllerInput = "net/minecraft/client/input/controller/ControllerInput", buttonDesc = "Lnet/minecraft/client/input/controller/Button;";
    rewrite(root.resolve("net/minecraft/client/gui/Screen.class"), next -> new ClassVisitor(Opcodes.ASM9, next) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        if (!name.equals("guiSpecificControllerInput") || !desc.equals("(" + CONTROLLER_INPUT_DESC + ")V")) return visitor;
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
            if (opcode == Opcodes.GETFIELD && owner.equals(controllerInput) && fieldName.equals("buttonY")) {
              super.visitMethodInsn(Opcodes.INVOKESTATIC, SKIN, "rightClickButton", "(L" + controllerInput + ";)" + buttonDesc, false); xyReads[0]++; return;
            }
            if (opcode == Opcodes.GETFIELD && owner.equals(controllerInput) && fieldName.equals("buttonX")) {
              super.visitMethodInsn(Opcodes.INVOKESTATIC, SKIN, "shiftClickButton", "(L" + controllerInput + ";)" + buttonDesc, false); xyReads[1]++; return;
            }
            super.visitFieldInsn(opcode, owner, fieldName, fieldDesc);
          }
        };
      }
    });
    System.out.println("screen Y=" + xyReads[0] + " X=" + xyReads[1]);
    if (xyReads[0] != 2 || xyReads[1] != 1) throw new IllegalStateException("unexpected X/Y reads");

    // OptionsPages.init(): after CONTROLS is stored, add the console options there
    int[] optionStores = new int[1];
    rewrite(root.resolve("net/minecraft/client/gui/options/data/OptionsPages.class"), next -> new ClassVisitor(Opcodes.ASM9, next) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        if (!name.equals("init") || !desc.equals("()V")) return visitor;
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
            super.visitFieldInsn(opcode, owner, fieldName, fieldDesc);
            if (opcode == Opcodes.PUTSTATIC && fieldName.equals("CONTROLS")) {
              super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/betterthanadventure/btamobile/meshfix/MobileOptions", "addControlsCategory", "()V", false);
              optionStores[0]++;
            }
          }
        };
      }
    });
    System.out.println("controls options=" + optionStores[0]);
    if (optionStores[0] != 1) throw new IllegalStateException("unexpected CONTROLS stores");
  }

  interface VisitorWrapper { ClassVisitor wrap(ClassVisitor next); }
  static void rewrite(Path file, VisitorWrapper wrapper) throws Exception {
    ClassReader reader = new ClassReader(Files.readAllBytes(file));
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    reader.accept(wrapper.wrap(writer), 0);
    Files.write(file, writer.toByteArray());
  }
}
