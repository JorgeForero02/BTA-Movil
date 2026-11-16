import java.nio.*;
import java.nio.file.*;
import java.util.Random;
import net.minecraft.client.render.tessellator.TessellatorTerrainImpl;
/** Writes to a file the bytes the real TessellatorTerrainImpl produces (for the GPU test). Args: output seed */
public class VertexDumper {
  public static void main(String[] a) throws Exception {
    TessellatorTerrainImpl t = new TessellatorTerrainImpl(0x100000);
    Random r = new Random(Long.parseLong(a[1]));
    t.startDrawingQuads();
    double tx = -3 * 512.0, tz = 7 * 512.0;
    t.setTranslation(tx, 0.0, tz);
    for (int q = 0; q < 2000; q++) {
      t.setColor1i(r.nextInt(0x1000000)); t.setNormal(r.nextInt(3) - 1, r.nextInt(3) - 1, r.nextInt(3) - 1);
      t.setShade1i(r.nextInt(256)); t.setLightmapCoord1i(r.nextInt(256));
      for (int v = 0; v < 4; v++) {
        double lx = r.nextBoolean() ? r.nextInt(512 * 16) / 16.0 : r.nextDouble() * 514 - 1;
        double ly = r.nextBoolean() ? r.nextInt(256 * 16) / 16.0 : r.nextDouble() * 258 - 1;
        double lz = r.nextBoolean() ? r.nextInt(512 * 16) / 16.0 : r.nextDouble() * 514 - 1;
        t.setTextureUV(r.nextDouble(), r.nextDouble());
        t.addVertex(lx - tx, ly, lz - tz);
      }
    }
    ByteBuffer b = t.data.getBuffer();
    byte[] out = new byte[b.position()]; b.get(0, out);
    Files.write(Path.of(a[0]), out);
    System.out.println(a[0] + ": " + t.data.vertexCount + " vertices, " + out.length + " bytes, " + t.data.config.getVertexSize() + " per vertex, order " + b.order());
  }
}
