import java.io.IOException;

/** Installer.checkOriginal: only the exact BTA 8.0.1 bta.jar is accepted. Any other jar stops the
 *  installer before building anything, because entries that BTA-Movil does not change are copied as
 *  they come (a tampered or different bta.jar would end up inside BTA-Movil.jar). */
public class InstallerTest {
  public static void main(String[] a) throws Exception {
    boolean ok = true;
    for (byte[] other : new byte[][] { new byte[0], "not BTA".getBytes(), new byte[1000] }) {
      try { Installer.checkOriginal(other); ok = false; System.out.println("accepted a jar that is not 8.0.1 (" + other.length + " bytes)"); }
      catch (IOException e) { if (!e.getMessage().contains("8.0.1")) { ok = false; System.out.println("message without the cause: " + e.getMessage()); } }
    }
    java.nio.file.Path t = java.nio.file.Files.createTempFile("bta-movil-test", ".jar");
    if (!Installer.written(t).startsWith("Replaced")) { ok = false; System.out.println("does not warn when replacing an existing file"); }
    java.nio.file.Files.delete(t);
    if (!Installer.written(t).startsWith("Created")) { ok = false; System.out.println("says it replaces a file that does not exist"); }
    System.out.println(ok ? "InstallerTest: OK (a bta.jar that is not exactly 8.0.1 stops the installer; warns on replace)" : "InstallerTest: FAILED");
    if (!ok) System.exit(1);
  }
}
