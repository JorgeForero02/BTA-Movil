import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.commons.AdviceAdapter;
import java.nio.file.*;
import java.util.*;
/** Inserts Profiler.enter/exit probes and makes the initial terrain VBO size configurable. */
public class ProfPatch {
  static final String PROFILER = "net/betterthanadventure/btamobile/meshfix/Profiler";
  // class, method, desc, id
  static final Object[][] PROBES = {
    {"net/minecraft/client/render/WorldRenderer", "updateCameraAndRender", "(F)V", 0},
    {"net/minecraft/client/Minecraft", "runTick", "()V", 1},
    {"net/minecraft/core/world/World", "tick", "()V", 2},
    {"net/minecraft/core/world/World", "updatingLighting", "()Z", 3},
    {"net/minecraft/core/world/World", "updateEntities", "()V", 4},
    {"net/minecraft/core/world/chunk/provider/ChunkProviderThreaded", "tick", "()Z", 5},
    {"net/minecraft/core/world/chunk/provider/ChunkProviderThreaded", "decorate", "(Lnet/minecraft/core/world/chunk/Chunk;)V", 6},
    {"net/minecraft/core/world/chunk/provider/ChunkProviderThreaded", "loadChunk", "(Lnet/minecraft/core/world/pos/ChunkPosc;)Lnet/minecraft/core/world/chunk/Chunk;", 7},
    {"net/minecraft/core/world/chunk/provider/ChunkProviderThreaded", "saveChunk", "(Lnet/minecraft/core/world/chunk/Chunk;)V", 8},
    {"net/minecraft/core/world/chunk/provider/ChunkProviderThreaded", "addChunk", "(Lnet/minecraft/core/world/chunk/Chunk;)V", 9},
    {"net/minecraft/client/render/RenderGlobal", "updateDirtyChunks", "(Lnet/minecraft/client/render/camera/ICamera;)Z", 10},
    {"net/minecraft/client/render/terrain/VertexBuffer", "expandVBO", "()V", 11},
    {"net/minecraft/core/world/World", "updateBlocksAndPlayCaveSounds", "()V", 12},
    {"net/minecraft/core/world/World", "tickPendingTicks", "(Z)Z", 13},
    {"net/minecraft/client/render/RenderGlobal", "render", "(Lnet/minecraft/client/render/camera/ICamera;ID)I", 14},
    {"net/minecraft/client/render/RenderGlobal", "resortChunks", "(III)V", 15},
    {"net/minecraft/client/render/terrain/TerrainRendererMultiDraw", "renderSolidTerrain", "(F)V", 18},
    {"net/minecraft/client/render/terrain/TerrainRendererMultiDraw", "renderTranslucentTerrain", "(F)V", 19},
    {"net/minecraft/client/render/TextureManager", "updateDynamicTextures", "(Z)V", 20},
    {"net/minecraft/core/world/World", "randomDisplayUpdates", "(Lnet/minecraft/core/world/pos/TilePos;)V", 21},
    {"net/minecraft/client/render/ParticleEngine", "tick", "()V", 22},
    {"net/minecraft/client/render/RenderGlobal", "tick", "()V", 23},
    {"net/minecraft/client/render/WorldRenderer", "updateRenderer", "()V", 24},
    {"net/minecraft/client/render/WorldRenderer", "getMouseOver", "(F)V", 25},
  };
  /** Exact counts expected in bta.jar 8.0.1: probes, VBO size, save limit and timing, tick radius, textures. */
  static final Map<String, Integer> EXPECTED = new TreeMap<>(Map.of(
      "Profiler.enter", 24, "vboSize", 1, "saveLimit", 1, "saveBegin", 1, "tickRadius", 5, "TextureUploadSkip.update", 1, "tickable", 1, "TextureUploadSkip.clear", 2));
  static final Map<String, Integer> HOOKS = new TreeMap<>();
  static void hit(String name) { HOOKS.merge(name, 1, Integer::sum); }
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Map<String, List<Object[]>> byClass = new LinkedHashMap<>();
    for (Object[] probe : PROBES) byClass.computeIfAbsent((String) probe[0], key -> new ArrayList<>()).add(probe);
    byClass.computeIfAbsent("net/minecraft/client/render/terrain/TerrainRendererMultiDraw", key -> new ArrayList<>());
    int probes = 0;
    for (var entry : byClass.entrySet()) {
      Path file = root.resolve(entry.getKey() + ".class");
      ClassReader reader = new ClassReader(Files.readAllBytes(file));
      ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
      int[] probeCount = {0};
      List<Object[]> list = entry.getValue();
      reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
          MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
          if (entry.getKey().endsWith("TerrainRendererMultiDraw") && name.equals("<init>")) {
            visitor = new MethodVisitor(Opcodes.ASM9, visitor) {
              public void visitLdcInsn(Object value) {
                if (value instanceof Long && (Long) value == 0x8000000L) { super.visitMethodInsn(Opcodes.INVOKESTATIC, PROFILER, "vboSize", "()J", false); hit("vboSize"); return; }
                super.visitLdcInsn(value);
              }
            };
          }
          if (entry.getKey().endsWith("ChunkProviderThreaded") && name.equals("tick") && desc.equals("()Z")) {
            visitor = new MethodVisitor(Opcodes.ASM9, visitor) {
              int seen = 0;
              public void visitInsn(int opcode) {
                if (opcode == Opcodes.ICONST_4 && ++seen == 2) {
                  // stack: [i] -> [i, i, loadedChunks] -> [i, saveLimit(i, loadedChunks)] for the if_icmple
                  super.visitInsn(Opcodes.DUP);
                  super.visitVarInsn(Opcodes.ALOAD, 0);
                  super.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/core/world/chunk/provider/ChunkProviderThreaded", "loadedChunks", "Ljava/util/Map;");
                  super.visitMethodInsn(Opcodes.INVOKESTATIC, PROFILER, "saveLimit", "(ILjava/util/Map;)I", false); hit("saveLimit"); return;
                }
                super.visitInsn(opcode);
              }
              public void visitMethodInsn(int opcode, String owner, String calleeName, String calleeDesc, boolean isInterface) {
                // times each save: Profiler.saveBegin() right before saveChunk(chunk) (arguments already on the stack)
                if (calleeName.equals("saveChunk") && owner.endsWith("ChunkProviderThreaded") && calleeDesc.equals("(Lnet/minecraft/core/world/chunk/Chunk;)V")) {
                  super.visitMethodInsn(Opcodes.INVOKESTATIC, PROFILER, "saveBegin", "()V", false); hit("saveBegin");
                }
                super.visitMethodInsn(opcode, owner, calleeName, calleeDesc, isInterface);
              }
            };
          }
          if (entry.getKey().equals("net/minecraft/core/world/World") && name.equals("updateBlocksAndPlayCaveSounds") && desc.equals("()V")) {
            visitor = new MethodVisitor(Opcodes.ASM9, visitor) {
              public void visitIntInsn(int opcode, int operand) {
                if (opcode == Opcodes.BIPUSH && (operand == 9 || operand == -9)) {
                  super.visitVarInsn(Opcodes.ALOAD, 0);
                  super.visitMethodInsn(Opcodes.INVOKESTATIC, PROFILER, "tickRadius", "(Lnet/minecraft/core/world/World;)I", false);
                  if (operand < 0) super.visitInsn(Opcodes.INEG);
                  hit("tickRadius"); return;
                }
                super.visitIntInsn(opcode, operand);
              }
            };
          }
          if (entry.getKey().equals("net/minecraft/client/render/TextureManager") && name.equals("updateDynamicTextures")) {
            visitor = new MethodVisitor(Opcodes.ASM9, visitor) {
              public void visitMethodInsn(int opcode, String owner, String calleeName, String calleeDesc, boolean isInterface) {
                if (opcode == Opcodes.INVOKEVIRTUAL && calleeName.equals("updateDynamicTexture") && owner.equals("net/minecraft/client/render/TextureManager")) {
                  super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/render/TextureUploadSkip", "update", "(Lnet/minecraft/client/render/TextureManager;Lnet/minecraft/client/render/dynamictexture/DynamicTexture;)V", false);
                  hit("TextureUploadSkip.update"); return;
                }
                super.visitMethodInsn(opcode, owner, calleeName, calleeDesc, isInterface);
              }
            };
          }
          if (entry.getKey().equals("net/minecraft/core/world/World") && name.equals("updateBlocksAndPlayCaveSounds") && desc.equals("()V")) {
            visitor = new MethodVisitor(Opcodes.ASM9, visitor) {
              public void visitMethodInsn(int opcode, String owner, String calleeName, String calleeDesc, boolean isInterface) {
                if (opcode == Opcodes.INVOKEVIRTUAL && calleeName.equals("isChunkLoaded") && calleeDesc.equals("(II)Z")) {
                  super.visitMethodInsn(Opcodes.INVOKESTATIC, PROFILER, "tickable", "(Lnet/minecraft/core/world/World;II)Z", false); hit("tickable"); return;
                }
                super.visitMethodInsn(opcode, owner, calleeName, calleeDesc, isInterface);
              }
            };
          }
          if (entry.getKey().equals("net/minecraft/client/render/TextureManager") && (name.equals("refreshTextures") || name.equals("initDynamicTextures"))) {
            visitor = new MethodVisitor(Opcodes.ASM9, visitor) {
              public void visitCode() { super.visitCode(); super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/render/TextureUploadSkip", "clear", "()V", false); hit("TextureUploadSkip.clear"); }
            };
          }
          for (Object[] probe : list) {
            if (probe[1].equals(name) && probe[2].equals(desc)) {
              int id = (Integer) probe[3]; probeCount[0]++;
              return new AdviceAdapter(Opcodes.ASM9, visitor, access, name, desc) {
                protected void onMethodEnter() { push(id); visitMethodInsn(INVOKESTATIC, PROFILER, "enter", "(I)V", false); }
                protected void onMethodExit(int opcode) { push(id); visitMethodInsn(INVOKESTATIC, PROFILER, "exit", "(I)V", false); }
              };
            }
          }
          return visitor;
        }
      }, ClassReader.EXPAND_FRAMES);
      if (probeCount[0] != list.size()) throw new IllegalStateException(entry.getKey() + ": probes " + probeCount[0] + "/" + list.size());
      probes += probeCount[0];
      Files.write(file, writer.toByteArray());
    }
    HOOKS.put("Profiler.enter", probes);
    HOOKS.forEach((key, value) -> System.out.println(value + "  " + key));
    boolean ok = true;
    for (var entry : EXPECTED.entrySet()) {
      int got = HOOKS.getOrDefault(entry.getKey(), 0);
      if (got != entry.getValue()) { System.err.println("ProfPatch: " + entry.getKey() + " = " + got + ", expected " + entry.getValue()); ok = false; }
    }
    if (!ok) System.exit(1);
  }
}
