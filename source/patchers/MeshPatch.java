import jdk.internal.org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
/** 1) TerrainRendererMultiDraw creates AsyncChunkRenderer. 2) shared scratch state of block models (static RenderBlocks,
 *  dragonfly LIGHTING_CACHE and vertex vectors, portal queryPos) is read through MesherRenderBlocks, per thread. */
public class MeshPatch {
  static final String RB_DESC = "Lnet/minecraft/client/render/block/model/RenderBlocks;";
  static final String STATIC_BLOCK_MODEL = "org/useless/dragonfly/models/block/mojang/StaticBlockModelMojang";
  static final String GENERIC_MODEL_PACKAGE = "net/minecraft/client/render/block/model/generic/";
  /** Per-instance scratch fields of block models (models are shared per block type): owner.field -> helper in MesherRenderBlocks. */
  static final String FLUID = "net/minecraft/client/render/block/model/BlockModelFluid";
  static final Map<String, String> SCRATCH = Map.ofEntries(
      Map.entry(STATIC_BLOCK_MODEL + ".vertPos", "vertPos"), Map.entry(STATIC_BLOCK_MODEL + ".faceVec", "faceVec"), Map.entry(STATIC_BLOCK_MODEL + ".vertUV", "vertUV"),
      Map.entry(GENERIC_MODEL_PACKAGE + "BlockModelGeneric.queryPos", "genericQueryPos"),
      Map.entry(GENERIC_MODEL_PACKAGE + "BlockModelGenericLayer.queryPos", "layerQueryPos"),
      Map.entry(GENERIC_MODEL_PACKAGE + "BlockModelGenericSlab.queryPos", "slabQueryPos"),
      // BlockModelFluid's scratch vectors (written while drawing water/lava): per thread so several mesher workers can run
      Map.entry(FLUID + ".br", "fluidBr"), Map.entry(FLUID + ".bl", "fluidBl"), Map.entry(FLUID + ".tr", "fluidTr"),
      Map.entry(FLUID + ".a", "fluidA"), Map.entry(FLUID + ".b", "fluidB"), Map.entry(FLUID + ".norm", "fluidNorm"),
      // BlockModelFluid.random: setSeed + nextInt picks the bubble overlay orientation; shared between threads it could mix
      Map.entry(FLUID + ".random", "fluidRandom"));
  /** Exact counts expected in bta.jar 8.0.1 (keys exactly as the code counts them). */
  static final Map<String, Integer> EXPECTED = new TreeMap<>(Map.ofEntries(
      Map.entry("INIT AsyncChunkRenderer", 1), Map.entry("NEW AsyncChunkRenderer", 1),
      Map.entry("LIGHTING_CACHE reads", 19), Map.entry("portal queryPos reads", 4), Map.entry("renderBlocks reads", 185),
      Map.entry("scratch fields widened", 13),
      Map.entry("scratch reads faceVec", 3), Map.entry("scratch reads fluidA", 2), Map.entry("scratch reads fluidB", 2),
      Map.entry("scratch reads fluidBl", 3), Map.entry("scratch reads fluidBr", 2), Map.entry("scratch reads fluidNorm", 2),
      Map.entry("scratch reads fluidRandom", 2), Map.entry("scratch reads fluidTr", 2),
      Map.entry("scratch reads genericQueryPos", 1), Map.entry("scratch reads layerQueryPos", 1),
      Map.entry("scratch reads slabQueryPos", 1), Map.entry("scratch reads vertPos", 4), Map.entry("scratch reads vertUV", 4)));
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Map<String,Integer> counts = new TreeMap<>();
    List<Path> files;
    try (Stream<Path> stream = Files.walk(root)) { files = stream.filter(path -> path.toString().endsWith(".class")).collect(Collectors.toList()); }
    int changedFiles = 0;
    for (Path file : files) {
      String fileName = file.toString();
      if (fileName.contains("betterthanadventure/")) continue; // shim classes (our other classes are added after patching)
      ClassReader reader = new ClassReader(Files.readAllBytes(file));
      String className = reader.getClassName();
      ClassWriter writer = new ClassWriter(reader, 0);
      boolean[] changed = {false};
      reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
          if (SCRATCH.containsKey(className + "." + name)) { // read by MesherRenderBlocks on the main thread
            access = (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
            counts.merge("scratch fields widened", 1, Integer::sum); changed[0] = true;
          }
          return super.visitField(access, name, desc, signature, value);
        }
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
          MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
          boolean clinit = name.equals("<clinit>");
          return new MethodVisitor(Opcodes.ASM9, visitor) {
            public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
              if (opcode == Opcodes.GETSTATIC && fieldName.equals("renderBlocks") && fieldDesc.equals(RB_DESC) && !clinit) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/render/block/model/MesherRenderBlocks", "renderBlocks", "()" + RB_DESC, false);
                counts.merge("renderBlocks reads", 1, Integer::sum); changed[0] = true; return;
              }
              String helper = SCRATCH.get(owner + "." + fieldName);
              if (opcode == Opcodes.GETFIELD && helper != null && !name.equals("<init>")) {
                // model instance on the stack -> per-thread scratch object (worker) or the model's own (main thread)
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/render/block/model/MesherRenderBlocks", helper, "(L" + owner + ";)" + fieldDesc, false);
                counts.merge("scratch reads " + helper, 1, Integer::sum); changed[0] = true; return;
              }
              if (opcode == Opcodes.GETSTATIC && !clinit && fieldName.equals("LIGHTING_CACHE") && fieldDesc.equals("Lnet/minecraft/client/render/LightingCache;")
                  && owner.equals("org/useless/dragonfly/models/block/mojang/StaticBlockModelMojang")) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/render/block/model/MesherRenderBlocks", "lightingCache", "()" + fieldDesc, false);
                counts.merge("LIGHTING_CACHE reads", 1, Integer::sum); changed[0] = true; return;
              }
              if (opcode == Opcodes.GETSTATIC && !clinit && fieldName.equals("queryPos") && fieldDesc.equals("Lnet/minecraft/core/world/pos/TilePos;")
                  && owner.equals("net/minecraft/client/render/block/model/generic/BlockModelGenericPortal$1Cache")) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/render/block/model/MesherRenderBlocks", "portalQueryPos", "()" + fieldDesc, false);
                counts.merge("portal queryPos reads", 1, Integer::sum); changed[0] = true; return;
              }
              super.visitFieldInsn(opcode, owner, fieldName, fieldDesc);
            }
            public void visitTypeInsn(int opcode, String type) {
              if (opcode == Opcodes.NEW && type.equals("net/minecraft/client/render/terrain/ChunkRendererMultiDraw") && className.equals("net/minecraft/client/render/terrain/TerrainRendererMultiDraw")) {
                type = "net/minecraft/client/render/terrain/AsyncChunkRenderer"; counts.merge("NEW AsyncChunkRenderer", 1, Integer::sum); changed[0] = true;
              }
              super.visitTypeInsn(opcode, type);
            }
            public void visitMethodInsn(int opcode, String owner, String calleeName, String calleeDesc, boolean isInterface) {
              if (opcode == Opcodes.INVOKESPECIAL && calleeName.equals("<init>") && owner.equals("net/minecraft/client/render/terrain/ChunkRendererMultiDraw") && className.equals("net/minecraft/client/render/terrain/TerrainRendererMultiDraw")) {
                owner = "net/minecraft/client/render/terrain/AsyncChunkRenderer"; counts.merge("INIT AsyncChunkRenderer", 1, Integer::sum); changed[0] = true;
              }
              super.visitMethodInsn(opcode, owner, calleeName, calleeDesc, isInterface);
            }
          };
        }
      }, 0);
      if (changed[0]) { Files.write(file, writer.toByteArray()); changedFiles++; }
    }
    counts.forEach((key, value) -> System.out.println(value + "  " + key));
    System.out.println("classes changed: " + changedFiles);
    boolean ok = true;
    for (var entry : EXPECTED.entrySet()) {
      int got = counts.getOrDefault(entry.getKey(), 0);
      if (got != entry.getValue()) { System.err.println("MeshPatch: " + entry.getKey() + " = " + got + ", expected " + entry.getValue()); ok = false; }
    }
    for (var entry : counts.entrySet()) {
      if (!EXPECTED.containsKey(entry.getKey())) { System.err.println("MeshPatch: " + entry.getKey() + " = " + entry.getValue() + ", expected 0"); ok = false; }
    }
    if (!ok) System.exit(1);
  }
}
