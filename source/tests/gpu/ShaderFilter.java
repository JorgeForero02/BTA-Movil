import java.nio.file.*;
import net.betterthanadventure.btamobile.meshfix.VertexPack;
public class ShaderFilter { public static void main(String[] a) throws Exception {
  String s = Files.readString(Path.of(a[0])); Files.writeString(Path.of(a[1]), VertexPack.filter(s).toString()); } }
