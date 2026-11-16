import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.zip.*;
import javax.swing.*;

/**
 * BTA-Movil installer. Turns the ORIGINAL bta.jar of BTA 8.0.1 into BTA-Movil.jar using binary deltas
 * (this installer contains no BTA code), checks every result by SHA-1, and writes:
 *   - BTA-Movil.jar
 *   - BTA-Movil-<ver>-Amethyst.zip  (unzip into .minecraft)
 *   - BTA-Movil-<ver>-Zalith.zip    (import as modpack)
 *   - optionally installs straight into a .minecraft folder (versions/BTA-Movil/).
 * Without arguments and with a screen it opens a window (Amethyst/Zalith "run .jar"); otherwise CLI.
 */
public final class Installer {
  static final String VERSION = "1.0.0";
  static final String SRC_SHA1 = "ff8cb27732a8e4bfd003200210ea10017199bf3f";
  static final long SRC_SIZE = 25788464L;
  static final long FIXED_TIME = 1784995200000L; // fixed entry dates (2026-07-25) for reproducible output

  interface Log { void line(String s); }

  // ------------------------------------------------------------------ entry point

  public static void main(String[] args) throws Exception {
    boolean gui = args.length == 0 && !GraphicsEnvironment.isHeadless();
    if (gui) {
      try { Gui.open(); return; } catch (Throwable t) { System.out.println("No window available (" + t + "), using text mode."); }
    }
    cli(args);
  }

  static void cli(String[] args) throws Exception {
    Log log = System.out::println;
    String in = null, out = null, install = null;
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("--install")) {
        if (i + 1 >= args.length) { log.line("Missing folder after --install"); System.exit(1); }
        install = args[++i];
      }
      else if (in == null) in = args[i];
      else if (out == null) out = args[i];
    }
    if (in == null) {
      log.line("BTA-Movil " + VERSION + " - installer");
      log.line("Usage: java -jar BTA-Movil-Installer.jar <bta.jar | bta.v8.0.1.mmc.zip | folder to search> [output_folder] [--install <.minecraft>]");
      List<Path> found = search(defaultSearchDirs(), log);
      if (found.isEmpty()) { System.exit(1); }
      in = found.get(0).toString();
      log.line("Using: " + in);
    }
    Path src = Paths.get(in);
    if (Files.isDirectory(src)) {
      List<Path> found = search(List.of(src), log);
      if (found.isEmpty()) { log.line("BTA 8.0.1 bta.jar not found in " + src); System.exit(1); }
      src = found.get(0);
    }
    Path outDir = out != null ? Paths.get(out) : (src.toAbsolutePath().getParent() != null ? src.toAbsolutePath().getParent() : Paths.get("."));
    try {
      run(src, outDir, install != null ? Paths.get(install) : null, true, log);
    } catch (IOException e) {
      log.line("ERROR: " + e.getMessage());
      System.exit(1);
    }
  }

  // ------------------------------------------------------------------ main job

  static void run(Path input, Path outDir, Path minecraftDir, boolean packages, Log log) throws Exception {
    log.line("BTA-Movil " + VERSION);
    log.line("Original: " + input);
    byte[] bta = readOriginal(input);
    checkOriginal(bta);
    log.line("bta.jar 8.0.1 verified.");

    byte[] jar = buildJar(bta, log);
    outDir = writableOut(outDir, input, minecraftDir, log);
    Path jarOut = outDir.resolve("BTA-Movil.jar");
    String vj = written(jarOut);
    Files.write(jarOut, jar);
    log.line(vj + jarOut + " (" + jar.length + " bytes)");

    byte[] json = res("templates/BTA-Movil.json"), readme = res("templates/README.txt"), opts = res("templates/recommended-options.txt");
    if (packages) {
      Path am = outDir.resolve("BTA-Movil-" + VERSION + "-Amethyst.zip");
      String va = written(am);
      try (ZipOutputStream z = zipOut(am)) {
        put(z, "README.txt", readme);
        put(z, "recommended-options.txt", opts);
        put(z, "BTA-Movil-controls.json", res("templates/BTA-Movil-controls.json"));
        put(z, "versions/BTA-Movil/BTA-Movil.jar", jar);
        put(z, "versions/BTA-Movil/BTA-Movil.json", json);
      }
      log.line(va + am);
      Path zl = outDir.resolve("BTA-Movil-" + VERSION + "-Zalith.zip");
      String vz = written(zl);
      try (ZipOutputStream z = zipOut(zl)) {
        put(z, "mmc-pack.json", res("templates/mmc-pack.json"));
        put(z, "instance.cfg", res("templates/instance.cfg"));
        put(z, "README.txt", readme);
        put(z, "BTA-Movil-controls.json", res("templates/BTA-Movil-controls.json"));
        put(z, ".minecraft/icon.png", res("templates/icon.png"));
        put(z, ".minecraft/BTA-Movil.jar", jar);
        put(z, ".minecraft/BTA-Movil.json", json);
        put(z, ".minecraft/options.txt", opts);
      }
      log.line(vz + zl);
    }
    if (minecraftDir != null) {
      Path v = minecraftDir.resolve("versions").resolve("BTA-Movil");
      try {
        Files.createDirectories(v);
        if (Files.exists(v.resolve("BTA-Movil.jar"))) log.line("Replacing the previous installation in " + v);
        Files.write(v.resolve("BTA-Movil.jar"), jar);
        Files.write(v.resolve("BTA-Movil.json"), json);
        log.line("Installed in: " + v + "  (pick the BTA-Movil version in the launcher)");
      } catch (SecurityException | IOException e) {
        // the packages above are already created: say how to finish by hand instead of failing
        log.line("Could not install in " + v + " (" + (e instanceof SecurityException ? "the launcher does not grant permission" : e.getMessage()) + ").");
        log.line("Do it by hand: unzip " + outDir.resolve("BTA-Movil-" + VERSION + "-Amethyst.zip") + " and copy its versions/BTA-Movil folder to " + minecraftDir.resolve("versions"));
      }
    }
    log.line("DONE.");
  }

  /** Only the exact BTA 8.0.1 bta.jar: entries BTA-Movil does not change are copied as they come, so
   *  any other jar would put its own (possibly tampered) classes inside BTA-Movil.jar. */
  static void checkOriginal(byte[] bta) throws Exception {
    String s = sha1(bta);
    if (!s.equals(SRC_SHA1)) throw new IOException("this bta.jar is not the official BTA 8.0.1 (sha1 " + s + ", expected " + SRC_SHA1 + "). Download bta.v8.0.1.mmc.zip again from the official page.");
  }

  /** Reads bta.jar directly or from the MultiMC/Prism zip (jarmods/bta.jar). */
  static byte[] readOriginal(Path p) throws IOException {
    byte[] b = Files.readAllBytes(p);
    try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(b))) {
      ZipEntry e;
      while ((e = z.getNextEntry()) != null)
        if (e.getName().equals("jarmods/bta.jar") || e.getName().endsWith("/jarmods/bta.jar")) return z.readAllBytes();
    }
    return b;
  }

  /** Copies every original entry, replaces changed ones through their delta, adds ours. */
  static byte[] buildJar(byte[] bta, Log log) throws Exception {
    Map<String, String[]> man = new LinkedHashMap<>();
    for (String line : new String(res("bta-movil/manifest.txt"), StandardCharsets.UTF_8).split("\n")) {
      String[] f = line.strip().split("\t");
      if (f.length == 5) man.put(f[1], f);
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream(27 << 20);
    Set<String> done = new HashSet<>();
    int changed = 0, added = 0, copied = 0;
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bta)); ZipOutputStream out = new ZipOutputStream(bos)) {
      out.setLevel(Deflater.DEFAULT_COMPRESSION);
      ZipEntry e;
      while ((e = in.getNextEntry()) != null) {
        String name = e.getName();
        if (!done.add(name)) continue;
        if (e.isDirectory()) { putDir(out, name); continue; }
        byte[] data = in.readAllBytes();
        String[] m = man.get(name);
        if (m != null && m[0].equals("D")) {
          if (!sha1(data).equals(m[2])) throw new IOException("File " + name + " does not match BTA 8.0.1: this bta.jar cannot be used.");
          data = applyDelta(data, res("bta-movil/p/" + m[4]));
          if (!sha1(data).equals(m[3])) throw new IOException("Wrong result in " + name);
          changed++;
        } else copied++;
        put(out, name, data);
      }
      for (String[] m : man.values()) {
        if (m[0].equals("D") && !done.contains(m[1])) throw new IOException("Missing " + m[1] + " in the bta.jar: it is not BTA 8.0.1.");
        if (!m[0].equals("A")) continue;
        byte[] data = res("bta-movil/p/" + m[4]);
        if (!sha1(data).equals(m[3])) throw new IOException("Damaged installer: " + m[1]);
        put(out, m[1], data);
        added++;
      }
    }
    log.line("Files: " + copied + " unchanged, " + changed + " modified, " + added + " new (all verified).");
    return bos.toByteArray();
  }

  static byte[] applyDelta(byte[] src, byte[] d) {
    ByteArrayOutputStream o = new ByteArrayOutputStream(src.length + 4096);
    int[] p = {0};
    while (true) {
      int op = d[p[0]++] & 0xff;
      if (op == 0) return o.toByteArray();
      if (op == 1) { int off = varint(d, p), len = varint(d, p); o.write(src, off, len); }
      else if (op == 2) { int len = varint(d, p); o.write(d, p[0], len); p[0] += len; }
      else throw new IllegalStateException("damaged delta");
    }
  }

  static int varint(byte[] d, int[] p) {
    int r = 0, sh = 0;
    while (true) { int b = d[p[0]++] & 0xff; r |= (b & 0x7f) << sh; sh += 7; if ((b & 0x80) == 0) return r; }
  }

  /** Log prefix for a file about to be written: says when an existing one is replaced (the installer
   *  overwrites without asking, also in a fallback folder chosen by writableOut). */
  static String written(Path p) { return Files.exists(p) ? "Replaced (already existed): " : "Created: "; }

  /**
   * The output folder, or the first writable alternative: launchers that run jars in a sandbox
   * (MojoLauncher: Java Security Manager) only allow writing in some folders, and the default may be
   * the launcher's private cache. Tried in order: the chosen folder, the folder of the original,
   * the folder above .minecraft, .minecraft itself, the user's home.
   */
  static Path writableOut(Path preferred, Path input, Path minecraftDir, Log log) throws IOException {
    List<Path> c = new ArrayList<>();
    if (preferred != null) c.add(preferred);
    Path ip = input.toAbsolutePath().getParent(); if (ip != null) c.add(ip);
    if (minecraftDir != null) { Path mp = minecraftDir.toAbsolutePath().getParent(); if (mp != null) c.add(mp); c.add(minecraftDir); }
    String uh = System.getProperty("user.home"); if (uh != null) c.add(Paths.get(uh));
    String why = null;
    for (Path d : c) {
      try {
        Files.createDirectories(d);
        Path t = Files.createTempFile(d, "bta-movil", ".tmp");
        Files.delete(t);
        if (d != preferred) log.line("Cannot write to " + preferred + " (" + why + "); using: " + d);
        return d;
      } catch (Throwable e) {
        if (why == null) why = e instanceof SecurityException ? "the launcher does not grant permission" : String.valueOf(e.getMessage());
      }
    }
    throw new IOException("no output folder is writable (" + why + "). Pick another one in \"Output folder\".");
  }

  // ------------------------------------------------------------------ search for bta.jar

  static List<Path> defaultSearchDirs() {
    LinkedHashSet<Path> dirs = new LinkedHashSet<>();
    String ud = System.getProperty("user.dir"), uh = System.getProperty("user.home");
    if (ud != null) dirs.add(Paths.get(ud));
    if (uh != null) { dirs.add(Paths.get(uh)); dirs.add(Paths.get(uh, ".minecraft")); }
    Path self = selfDir(); if (self != null) dirs.add(self);
    for (String a : new String[]{"/storage/emulated/0/Download", "/sdcard/Download", "/storage/emulated/0/games"})
      dirs.add(Paths.get(a));
    return new ArrayList<>(dirs);
  }

  static Path selfDir() {
    try { return Paths.get(Installer.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent(); }
    catch (Throwable t) { return null; }
  }

  /** Finds the original bta.jar (by size + SHA-1) or a MultiMC zip with jarmods/bta.jar, up to 5 folders deep. */
  static List<Path> search(List<Path> roots, Log log) {
    List<Path> found = new ArrayList<>();
    log.line("Looking for bta.jar 8.0.1...");
    for (Path r : roots) {
      if (!Files.isDirectory(r)) continue;
      try {
        Files.walkFileTree(r, EnumSet.noneOf(FileVisitOption.class), 5, new SimpleFileVisitor<Path>() {
          @Override public FileVisitResult visitFile(Path p, java.nio.file.attribute.BasicFileAttributes a) {
            String n = p.getFileName().toString().toLowerCase();
            boolean cand = a.isRegularFile() && (a.size() == SRC_SIZE || (n.endsWith(".zip") && n.contains("bta") && a.size() > SRC_SIZE / 2));
            if (cand) {
              try {
                Path abs = p.toAbsolutePath();
                if (!found.contains(abs)) {
                  byte[] b = readOriginal(p);
                  if (b.length == SRC_SIZE && sha1(b).equals(SRC_SHA1)) { found.add(abs); log.line("  found: " + p); }
                }
              } catch (Throwable ignored) { }
            }
            return FileVisitResult.CONTINUE;
          }
          @Override public FileVisitResult visitFileFailed(Path p, IOException e) { return FileVisitResult.CONTINUE; } // protected folders
        });
      } catch (Throwable ignored) { }
    }
    if (found.isEmpty()) log.line("Not found. Give the path to bta.jar or to the bta.v8.0.1.mmc.zip zip.");
    return found;
  }

  /** A .minecraft folder near where the launcher runs us (has a versions/ folder). */
  static Path guessMinecraft() {
    String ud = System.getProperty("user.dir"), uh = System.getProperty("user.home");
    List<Path> c = new ArrayList<>();
    if (ud != null) { c.add(Paths.get(ud)); c.add(Paths.get(ud, ".minecraft")); }
    if (uh != null) { c.add(Paths.get(uh, ".minecraft")); c.add(Paths.get(uh)); }
    for (Path p : c) if (Files.isDirectory(p.resolve("versions"))) return p.toAbsolutePath();
    return null;
  }

  // ------------------------------------------------------------------ helpers

  static byte[] res(String name) throws IOException {
    try (InputStream in = Installer.class.getResourceAsStream("/" + name)) {
      if (in == null) throw new IOException("Incomplete installer: missing " + name);
      return in.readAllBytes();
    }
  }

  static String sha1(byte[] b) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (byte x : MessageDigest.getInstance("SHA-1").digest(b)) sb.append(String.format("%02x", x));
    return sb.toString();
  }

  static ZipOutputStream zipOut(Path p) throws IOException { return new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(p))); }

  static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
    ZipEntry e = new ZipEntry(name); e.setTime(FIXED_TIME); z.putNextEntry(e); z.write(data); z.closeEntry();
  }

  static void putDir(ZipOutputStream z, String name) throws IOException {
    ZipEntry e = new ZipEntry(name); e.setTime(FIXED_TIME); z.putNextEntry(e); z.closeEntry();
  }

  // ------------------------------------------------------------------ window

  static final class Gui {
    static void open() throws Exception {
      SwingUtilities.invokeAndWait(Gui::build);
    }

    static void build() {
      JFrame f = new JFrame("BTA-Movil " + VERSION + " - Installer");
      f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
      JTextField src = new JTextField(), out = new JTextField(), mc = new JTextField();
      JCheckBox inst = new JCheckBox("Also install into this .minecraft folder (versions/BTA-Movil)");
      JTextArea log = new JTextArea(12, 50); log.setEditable(false); log.setLineWrap(true);
      Log L = s -> SwingUtilities.invokeLater(() -> { log.append(s + "\n"); log.setCaretPosition(log.getDocument().getLength()); });

      Path guess = guessMinecraft();
      if (guess != null) { mc.setText(guess.toString()); inst.setSelected(true); }
      Path self = selfDir();
      out.setText((self != null ? self : Paths.get(System.getProperty("user.home", "."))).toString());

      JPanel g = new JPanel(new GridBagLayout());
      GridBagConstraints c = new GridBagConstraints();
      c.insets = new Insets(3, 3, 3, 3); c.fill = GridBagConstraints.HORIZONTAL;
      row(g, c, 0, "Original bta.jar (or MultiMC zip):", src, chooser(f, src, false));
      row(g, c, 1, "Output folder (packages):", out, chooser(f, out, true));
      row(g, c, 2, ".minecraft folder:", mc, chooser(f, mc, true));
      c.gridx = 0; c.gridy = 3; c.gridwidth = 3; g.add(inst, c);

      JButton find = new JButton("Find bta.jar"), go = new JButton("Create BTA-Movil");
      JPanel b = new JPanel(); b.add(find); b.add(go);
      c.gridy = 4; g.add(b, c);

      find.addActionListener(ev -> new Thread(() -> {
        List<Path> dirs = new ArrayList<>(defaultSearchDirs());
        if (!mc.getText().isBlank()) dirs.add(0, Paths.get(mc.getText().trim()));
        List<Path> r = search(dirs, L);
        if (!r.isEmpty()) SwingUtilities.invokeLater(() -> src.setText(r.get(0).toString()));
      }).start());

      go.addActionListener(ev -> {
        go.setEnabled(false);
        new Thread(() -> {
          try {
            if (src.getText().isBlank()) throw new IOException("Pick the original bta.jar (or press Find).");
            Path m = inst.isSelected() && !mc.getText().isBlank() ? Paths.get(mc.getText().trim()) : null;
            run(Paths.get(src.getText().trim()), Paths.get(out.getText().trim()), m, true, L);
          } catch (Throwable t) {
            L.line("ERROR: " + t.getMessage());
          } finally {
            SwingUtilities.invokeLater(() -> go.setEnabled(true));
          }
        }).start();
      });

      JPanel root = new JPanel(new BorderLayout(4, 4));
      root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      root.add(g, BorderLayout.NORTH);
      root.add(new JScrollPane(log), BorderLayout.CENTER);
      f.setContentPane(root);
      f.pack();
      f.setLocationRelativeTo(null);
      f.setVisible(true);
      L.line("1) Pick your BTA 8.0.1 bta.jar (or the bta.v8.0.1.mmc.zip zip), or press Find.");
      L.line("2) Press Create. This builds BTA-Movil.jar and the Amethyst and Zalith packages.");
      if (guess != null) L.line("   Detected .minecraft: " + guess);
    }

    static void row(JPanel g, GridBagConstraints c, int y, String label, JTextField field, JButton btn) {
      c.gridy = y; c.gridwidth = 1;
      c.gridx = 0; c.weightx = 0; g.add(new JLabel(label), c);
      c.gridx = 1; c.weightx = 1; field.setColumns(28); g.add(field, c);
      c.gridx = 2; c.weightx = 0; g.add(btn, c);
    }

    static JButton chooser(JFrame f, JTextField target, boolean dirs) {
      JButton b = new JButton("...");
      b.addActionListener(ev -> {
        JFileChooser fc = new JFileChooser(target.getText().isBlank() ? System.getProperty("user.home") : target.getText());
        fc.setFileSelectionMode(dirs ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        if (fc.showOpenDialog(f) == JFileChooser.APPROVE_OPTION) target.setText(fc.getSelectedFile().getAbsolutePath());
      });
      return b;
    }
  }
}
