import jdk.internal.org.objectweb.asm.*;
import java.nio.file.*;
/** BlockModelFluid: water faces that touch a full see-through block are drawn FluidInset.GAP (0.005) into the water
 *  instead of 0.0001 / 0.001 / 0 from that block's face, with their edges moved so the water stays closed; no side or
 *  bottom face against permaice (opaque, ice material). Rules in FluidInset.
 *   - Sides: the 16 constants 1.0E-4 of the side cases (validated: operation sequence "-+++ +--- +++- ---+" and the
 *     locals they are stored in, "27 31 29 33" per case): the inward pair of each case (z in cases 0/1, x in 2/3)
 *     becomes FluidInset.side(world, pos, side), each end of the stretching pair FluidInset.stretchEnd(world, pos,
 *     side, -1 | +1) (side = local 21, the switch index).
 *   - Side-face vertices (after the 2nd tableswitch, its 4 addVertexWithUV): y of the 2 bottom vertices through
 *     FluidInset.sideBottom, height of the 2 top vertices through FluidInset.sideTop.
 *   - Top: right after the 4 corner heights (locals 16-19) each becomes min(h, FluidInset.topMax(world, pos, block)),
 *     kept in local 14, where BTA stores a 0.001f it never reads (checked: no FLOAD 14 in BTA's code).
 *   - Bottom: the 0.001f added to y becomes FluidInset.bottom(world, pos); RenderBlocks.renderBottomFace goes through
 *     FluidInset.bottomFace (moves its edges).
 *   - shouldSideBeRendered starts with "if (FluidInset.hidden(...) || FluidContact.filter(...) == 0) return false;"
 *     (args: world, neighbour, side, this.block; filter splits contact faces from the rest for the mesher).
 *   - TerrainRendererMultiDraw.renderTranslucentTerrain: FluidContact.beginTranslucent / endTranslucent around it,
 *     its 2 GLRenderer.setColorMask through FluidContact.colorMask (depth pre-pass or color pass), and its first
 *     2 renderAllLists through FluidContact.renderAllLists (contact faces of every region first, in the first that runs).
 *  Fails on any unexpected count. Args: <jar dir> */
public class FluidPatch {
  static final String FLUID_MODEL = "net/minecraft/client/render/block/model/BlockModelFluid", FLUID_INSET = "net/betterthanadventure/btamobile/meshfix/FluidInset",
      FLUID_CONTACT = "net/betterthanadventure/btamobile/meshfix/FluidContact", TERRAIN_RENDERER = "net/minecraft/client/render/terrain/TerrainRendererMultiDraw";
  static final String WORLD_SOURCE = "Lnet/minecraft/core/world/WorldSource;", POS = "Lnet/minecraft/core/world/pos/TilePosc;";
  static final String BOTTOM_FACE = "(Lnet/minecraft/client/render/tessellator/TessellatorGeneral;Lorg/joml/primitives/AABBdc;DDDLnet/minecraft/client/render/texture/stitcher/IconCoordinate;)V";
  /** Operation after each 1.0E-4 in render(): 0 = none (first one, stored as is), '+' dadd, '-' dsub. */
  static final String EXPECTED = "0" + "-+++" + "+---" + "+++-" + "---+";
  static final int[] INWARD = {3, 4, 7, 8, 9, 10, 13, 14};
  /** Compares a counter with its exact value; on a mismatch prints "FluidPatch: <counter> = N, expected M" to stderr. */
  static boolean exact(String counter, int got, int expected) {
    if (got == expected) return true;
    System.err.println("FluidPatch: " + counter + " = " + got + ", expected " + expected);
    return false;
  }
  public static void main(String[] args) throws Exception {
    Path fluidFile = Path.of(args[0], FLUID_MODEL + ".class");
    ClassReader reader = new ClassReader(Files.readAllBytes(fluidFile));
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
    StringBuilder seen = new StringBuilder(), stores = new StringBuilder(), heights = new StringBuilder();
    // local each 1.0E-4 ends up in (x0 27, z0 29, x1 31, z1 33): checks that the inward pair of cases 0/1 is z, of 2/3 x
    final String STORES = "35 " + "27 31 29 33 ".repeat(4);
    int[] replaced = {0}, top = {0}, local14Reads = {0}, small = {0}, bottom = {0}, stretched = {0}, hidden = {0}, sideEdges = {0, 0}, bottomFaces = {0}, bottomContext = {0};
    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        if (name.equals("shouldSideBeRendered") && desc.equals("(" + WORLD_SOURCE + "Lorg/joml/primitives/AABBdc;" + POS + "Lnet/minecraft/core/util/helper/Side;)Z")) {
          return new MethodVisitor(Opcodes.ASM9, visitor) {
            // if (FluidInset.hidden(world, neighbour, side, this.block) || FluidContact.filter(world, neighbour, side, this.block) == 0)
            //   return false;
            public void visitCode() {
              super.visitCode();
              Label no = new Label(), orig = new Label();
              String hookDesc = "(" + WORLD_SOURCE + POS + "Lnet/minecraft/core/util/helper/Side;Lnet/minecraft/core/block/Block;)";
              load();
              super.visitMethodInsn(Opcodes.INVOKESTATIC, FLUID_INSET, "hidden", hookDesc + "Z", false);
              super.visitJumpInsn(Opcodes.IFNE, no);
              load();
              super.visitMethodInsn(Opcodes.INVOKESTATIC, FLUID_CONTACT, "filter", hookDesc + "I", false);
              super.visitJumpInsn(Opcodes.IFNE, orig);
              super.visitLabel(no);
              super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
              super.visitInsn(Opcodes.ICONST_0);
              super.visitInsn(Opcodes.IRETURN);
              super.visitLabel(orig);
              super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
              hidden[0]++;
            }
            void load() {
              super.visitVarInsn(Opcodes.ALOAD, 1);
              super.visitVarInsn(Opcodes.ALOAD, 3);
              super.visitVarInsn(Opcodes.ALOAD, 4);
              super.visitVarInsn(Opcodes.ALOAD, 0);
              super.visitFieldInsn(Opcodes.GETFIELD, FLUID_MODEL, "block", "Lnet/minecraft/core/block/Block;");
            }
          };
        }
        if (!name.equals("render") || !desc.equals("(Lnet/minecraft/client/render/tessellator/TessellatorGeneral;" + WORLD_SOURCE + POS + ")Z")) return visitor;
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          boolean afterEps, awaitStore, expectFadd;
          // side-face vertex emission: after the 2nd tableswitch (the normal switch) and until its 4 addVertexWithUV
          int switches, vertices, pendingY;
          boolean inSides() { return switches >= 2 && vertices < 4; }
          void wrap(String method) {
            super.visitVarInsn(Opcodes.ALOAD, 2);
            super.visitVarInsn(Opcodes.ALOAD, 3);
            super.visitVarInsn(Opcodes.ILOAD, 21);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, FLUID_INSET, method, "(D" + WORLD_SOURCE + POS + "I)D", false);
          }
          void op(char symbol) { if (afterEps) { seen.append(symbol); afterEps = false; } }
          public void visitLdcInsn(Object value) {
            if (afterEps) op('0');
            pendingY = 0;
            if (value instanceof Double && (Double) value == 1.0E-4) {
              int index = seen.length();
              afterEps = true;
              awaitStore = true;
              boolean inward = java.util.Arrays.stream(INWARD).anyMatch(candidate -> candidate == index);
              if (index > 0) { // 0: BTA's unused first copy
                super.visitVarInsn(Opcodes.ALOAD, 2);
                super.visitVarInsn(Opcodes.ALOAD, 3);
                super.visitVarInsn(Opcodes.ILOAD, 21);
                if (inward) super.visitMethodInsn(Opcodes.INVOKESTATIC, FLUID_INSET, "side", "(" + WORLD_SOURCE + POS + "I)D", false);
                else {
                  super.visitInsn(EXPECTED.charAt(index) == '-' ? Opcodes.ICONST_M1 : Opcodes.ICONST_1); // low / high end of the width
                  super.visitMethodInsn(Opcodes.INVOKESTATIC, FLUID_INSET, "stretchEnd", "(" + WORLD_SOURCE + POS + "II)D", false);
                }
                if (inward) replaced[0]++; else stretched[0]++;
                return;
              }
            }
            if (value instanceof Float && (Float) value == 0.001f && small[0]++ == 1) { // 1st: BTA's unused local 14; 2nd: bottom face y
              super.visitVarInsn(Opcodes.ALOAD, 2);
              super.visitVarInsn(Opcodes.ALOAD, 3);
              super.visitMethodInsn(Opcodes.INVOKESTATIC, FLUID_INSET, "bottom", "(" + WORLD_SOURCE + POS + ")F", false);
              expectFadd = true;
              bottom[0]++;
              return;
            }
            super.visitLdcInsn(value);
          }
          public void visitInsn(int opcode) {
            if (expectFadd) { if (opcode != Opcodes.FADD) bottomContext[0]++; expectFadd = false; }
            op(opcode == Opcodes.DADD ? '+' : opcode == Opcodes.DSUB ? '-' : '0');
            super.visitInsn(opcode);
            pendingY = inSides() && opcode == Opcodes.I2D && pendingY == 1 ? 2 : 0;
          }
          public void visitVarInsn(int opcode, int var) {
            if (awaitStore && opcode == Opcodes.DSTORE) { stores.append(var).append(' '); awaitStore = false; }
            op('0');
            if (pendingY == 2 && opcode == Opcodes.DLOAD && (var == 29 || var == 33)) { wrap("sideBottom"); sideEdges[1]++; } // y of a bottom vertex
            boolean topH = pendingY == 2 && opcode == Opcodes.DLOAD && (var == 23 || var == 25);
            pendingY = inSides() && opcode == Opcodes.ILOAD && var == 7 ? 1 : 0;
            super.visitVarInsn(opcode, var);
            if (topH) { wrap("sideTop"); sideEdges[0]++; } // height of a top vertex
            if (opcode == Opcodes.FSTORE && var == 19 && top[0] == 0 && heights.toString().equals("16 17 18 ")) { // after the 4th corner height
              super.visitVarInsn(Opcodes.ALOAD, 2);
              super.visitVarInsn(Opcodes.ALOAD, 3);
              super.visitVarInsn(Opcodes.ALOAD, 0);
              super.visitFieldInsn(Opcodes.GETFIELD, FLUID_MODEL, "block", "Lnet/minecraft/core/block/Block;");
              super.visitMethodInsn(Opcodes.INVOKESTATIC, FLUID_INSET, "topMax", "(" + WORLD_SOURCE + POS + "Lnet/minecraft/core/block/Block;)F", false);
              super.visitVarInsn(Opcodes.FSTORE, 14);
              for (int corner = 16; corner <= 19; corner++) {
                super.visitVarInsn(Opcodes.FLOAD, corner);
                super.visitVarInsn(Opcodes.FLOAD, 14);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(FF)F", false);
                super.visitVarInsn(Opcodes.FSTORE, corner);
              }
              top[0]++;
            }
            if (var == 14 && opcode != Opcodes.FSTORE) local14Reads[0]++; // BTA must never read slot 14 (we reuse it)
            if (var == 13 && (opcode == Opcodes.DLOAD || opcode == Opcodes.DSTORE || opcode == Opcodes.LLOAD || opcode == Opcodes.LSTORE)) local14Reads[0]++;
            if (opcode == Opcodes.FSTORE && var >= 16 && var <= 19) heights.append(var).append(' ');
          }
          public void visitMethodInsn(int opcode, String owner, String calleeName, String calleeDesc, boolean isInterface) {
            op('0');
            pendingY = 0;
            if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/client/render/block/model/RenderBlocks") && calleeName.equals("renderBottomFace")) {
              if (!calleeDesc.equals(BOTTOM_FACE)) throw new IllegalStateException("FluidPatch: renderBottomFace" + calleeDesc);
              super.visitVarInsn(Opcodes.ALOAD, 2);
              super.visitVarInsn(Opcodes.ALOAD, 3);
              super.visitMethodInsn(Opcodes.INVOKESTATIC, FLUID_INSET, "bottomFace", "(Lnet/minecraft/client/render/block/model/RenderBlocks;" + calleeDesc.substring(1, calleeDesc.indexOf(')')) + WORLD_SOURCE + POS + ")V", false);
              bottomFaces[0]++;
              return;
            }
            super.visitMethodInsn(opcode, owner, calleeName, calleeDesc, isInterface);
            if (switches >= 2 && opcode == Opcodes.INVOKEINTERFACE && calleeName.equals("addVertexWithUV")) vertices++;
          }
          public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) { op('0'); pendingY = 0; super.visitFieldInsn(opcode, owner, fieldName, fieldDesc); }
          public void visitJumpInsn(int opcode, Label label) { op('0'); pendingY = 0; super.visitJumpInsn(opcode, label); }
          public void visitIntInsn(int opcode, int operand) { op('0'); pendingY = 0; super.visitIntInsn(opcode, operand); }
          public void visitIincInsn(int var, int inc) { op('0'); pendingY = 0; if (var == 14 || var == 13) local14Reads[0]++; super.visitIincInsn(var, inc); }
          public void visitTypeInsn(int opcode, String type) { op('0'); pendingY = 0; super.visitTypeInsn(opcode, type); }
          public void visitLookupSwitchInsn(Label defaultLabel, int[] keys, Label[] labels) { op('0'); pendingY = 0; super.visitLookupSwitchInsn(defaultLabel, keys, labels); }
          public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) { op('0'); pendingY = 0; super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs); }
          public void visitTableSwitchInsn(int low, int high, Label defaultLabel, Label... labels) { op('0'); pendingY = 0; switches++; super.visitTableSwitchInsn(low, high, defaultLabel, labels); }
        };
      }
    }, 0);
    boolean ok = true;
    if (!seen.toString().equals(EXPECTED)) { System.err.println("FluidPatch: operation sequence of BlockModelFluid.render = " + seen + ", expected " + EXPECTED); ok = false; }
    if (!stores.toString().equals(STORES)) { System.err.println("FluidPatch: locals of BlockModelFluid.render = " + stores + ", expected " + STORES); ok = false; }
    ok &= exact("sides", replaced[0], INWARD.length);
    ok &= exact("stretch", stretched[0], 8);
    ok &= exact("top", top[0], 1);
    ok &= exact("reads of local 14", local14Reads[0], 0);
    ok &= exact("0.001f", small[0], 2);
    ok &= exact("bottom", bottom[0], 1);
    ok &= exact("opaque neighbour", hidden[0], 1);
    ok &= exact("side top edges", sideEdges[0], 2);
    ok &= exact("side bottom edges", sideEdges[1], 2);
    ok &= exact("renderBottomFace", bottomFaces[0], 1);
    ok &= exact("bottom context", bottomContext[0], 0);
    if (!ok) System.exit(1);
    byte[] fluidBytes = writer.toByteArray(); // written with the other class, once both are validated

    // contact faces: translucent pass begin / end / color mask (FluidContact; MultiDraw draws them apart)
    int[] translucent = new int[5]; // begin, end, colorMask, renderAllLists seen, redirected
    Path terrainFile = Path.of(args[0], TERRAIN_RENDERER + ".class");
    ClassReader terrainReader = new ClassReader(Files.readAllBytes(terrainFile));
    ClassWriter terrainWriter = new ClassWriter(terrainReader, ClassWriter.COMPUTE_MAXS);
    terrainReader.accept(new ClassVisitor(Opcodes.ASM9, terrainWriter) {
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        if (!name.equals("renderTranslucentTerrain") || !desc.equals("(F)V")) return visitor;
        return new MethodVisitor(Opcodes.ASM9, visitor) {
          public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 0); // the terrain renderer: its VBO is the one the stored ranges belong to
            super.visitMethodInsn(Opcodes.INVOKESTATIC, FLUID_CONTACT, "beginTranslucent", "(Ljava/lang/Object;)V", false);
            translucent[0]++;
          }
          public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN) { super.visitMethodInsn(Opcodes.INVOKESTATIC, FLUID_CONTACT, "endTranslucent", "()V", false); translucent[1]++; }
            super.visitInsn(opcode);
          }
          public void visitMethodInsn(int opcode, String owner, String calleeName, String calleeDesc, boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC && owner.equals("net/minecraft/client/render/renderer/GLRenderer") && calleeName.equals("setColorMask") && calleeDesc.equals("(ZZZZ)V")) {
              super.visitMethodInsn(opcode, FLUID_CONTACT, "colorMask", calleeDesc, false); translucent[2]++; return;
            }
            if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(TERRAIN_RENDERER) && calleeName.equals("renderAllLists") && calleeDesc.equals("(I)V")) {
              translucent[3]++;
              super.visitMethodInsn(Opcodes.INVOKESTATIC, FLUID_CONTACT, "renderAllLists", "(L" + TERRAIN_RENDERER + ";I)V", false); translucent[4]++; return;
            }
            super.visitMethodInsn(opcode, owner, calleeName, calleeDesc, isInterface);
          }
        };
      }
    }, 0);
    boolean translucentOk = exact("translucent begin", translucent[0], 1);
    translucentOk &= exact("translucent end", translucent[1], 1);
    translucentOk &= exact("translucent colorMask", translucent[2], 2);
    translucentOk &= exact("renderAllLists seen", translucent[3], 2);
    translucentOk &= exact("renderAllLists redirected", translucent[4], 2);
    if (!translucentOk) System.exit(1);
    // both classes validated: each file written whole to a temporary one and moved in place (never a half-written
    // class; rebuild.sh always patches a fresh copy of the jar, so a run cut between the two is simply run again)
    Path fluidTmp = fluidFile.resolveSibling(fluidFile.getFileName() + ".tmp"), terrainTmp = terrainFile.resolveSibling(terrainFile.getFileName() + ".tmp");
    try {
      Files.write(fluidTmp, fluidBytes);
      Files.write(terrainTmp, terrainWriter.toByteArray());
      Files.move(fluidTmp, fluidFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      Files.move(terrainTmp, terrainFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(fluidTmp);
      Files.deleteIfExists(terrainTmp);
    }
    System.out.println("FluidPatch: translucent pass begin/end/colorMask/renderAllLists " + translucent[0] + "/" + translucent[1] + "/" + translucent[2] + "/" + translucent[4]);
    System.out.println("FluidPatch: sides " + replaced[0] + ", stretch " + stretched[0] + ", top 1, bottom 1, opaque neighbour " + hidden[0] + ", side edges " + sideEdges[0] + "+" + sideEdges[1] + ", bottom face " + bottomFaces[0] + " -> FluidInset");
  }
}
