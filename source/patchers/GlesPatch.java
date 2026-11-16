import jdk.internal.org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
/** Redirects GL calls GLES lacks to net/betterthanadventure/btamobile/glesfix/GlesShim and boots GLES mode in Minecraft.main. */
public class GlesPatch {
  static final String SHIM = "net/betterthanadventure/btamobile/glesfix/GlesShim";
  static final Set<String> NAMES = Set.of("glTexImage2D","glTexParameteri","glRenderbufferStorage","glRenderbufferStorageMultisample",
      "glTexImage2DMultisample","glClearDepth","glMapBuffer","glGetTexImage","glBegin","glEnd","glVertex2d","glColor3d","glOrtho","glLoadIdentity");
  /** Redirected calls expected in bta.jar 8.0.1 (LWJGL GL class simple name + method name + descriptor), plus the boot hook. */
  static final Map<String,Integer> EXPECTED = new TreeMap<>(Map.ofEntries(
      Map.entry("BOOT", 1),
      Map.entry("GL11.glBegin(I)V", 1), Map.entry("GL11.glColor3d(DDD)V", 1), Map.entry("GL11.glEnd()V", 1),
      Map.entry("GL11.glLoadIdentity()V", 1), Map.entry("GL11.glOrtho(DDDDDD)V", 1),
      Map.entry("GL11.glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V", 2), Map.entry("GL11.glTexParameteri(III)V", 8),
      Map.entry("GL11.glVertex2d(DD)V", 6), Map.entry("GL41.glClearDepth(D)V", 1),
      Map.entry("GL41.glGetTexImage(IIIILjava/nio/ByteBuffer;)V", 5), Map.entry("GL41.glMapBuffer(II)Ljava/nio/ByteBuffer;", 1),
      Map.entry("GL41.glRenderbufferStorage(IIII)V", 2), Map.entry("GL41.glRenderbufferStorageMultisample(IIIII)V", 1),
      Map.entry("GL41.glTexImage2D(IIIIIIIIJ)V", 2), Map.entry("GL41.glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V", 5),
      Map.entry("GL41.glTexImage2DMultisample(IIIIIZ)V", 1), Map.entry("GL41.glTexParameteri(III)V", 31)));
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Map<String,Integer> counts = new TreeMap<>();
    List<Path> files;
    try (Stream<Path> stream = Files.walk(root)) { files = stream.filter(path -> path.toString().endsWith(".class")).collect(Collectors.toList()); }
    int changedFiles = 0;
    for (Path file : files) {
      if (file.toString().contains("betterthanadventure/btamobile/glesfix") || file.toString().contains("betterthanadventure/btamobile/zalithfix")) continue;
      byte[] classBytes = Files.readAllBytes(file);
      ClassReader reader = new ClassReader(classBytes);
      boolean isMinecraft = reader.getClassName().equals("net/minecraft/client/Minecraft");
      ClassWriter writer = new ClassWriter(reader, 0);
      boolean[] changed = {false};
      reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
          MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
          boolean boot = isMinecraft && name.equals("main") && desc.equals("([Ljava/lang/String;)V");
          return new MethodVisitor(Opcodes.ASM9, visitor) {
            public void visitCode() {
              super.visitCode();
              if (boot) { super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/betterthanadventure/btamobile/glesfix/GlesMode", "boot", "()V", false); changed[0] = true; counts.merge("BOOT", 1, Integer::sum); }
            }
            public void visitMethodInsn(int opcode, String owner, String calleeName, String calleeDesc, boolean isInterface) {
              if (opcode == Opcodes.INVOKESTATIC && owner.startsWith("org/lwjgl/opengl/GL") && NAMES.contains(calleeName)) {
                counts.merge(owner.substring(owner.lastIndexOf('/')+1) + "." + calleeName + calleeDesc, 1, Integer::sum);
                owner = SHIM; changed[0] = true;
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
      if (got != entry.getValue()) { System.err.println("GlesPatch: " + entry.getKey() + " = " + got + ", expected " + entry.getValue()); ok = false; }
    }
    for (var entry : counts.entrySet()) {
      if (!EXPECTED.containsKey(entry.getKey())) { System.err.println("GlesPatch: " + entry.getKey() + " = " + entry.getValue() + ", expected 0"); ok = false; }
    }
    if (!ok) System.exit(1);
  }
}
