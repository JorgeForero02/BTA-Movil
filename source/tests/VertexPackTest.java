import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import net.betterthanadventure.btamobile.meshfix.VertexPack;
import net.minecraft.client.render.tessellator.TessellatorTerrainImpl;

/**
 * Uses the REAL, already patched TessellatorTerrainImpl: writes faces with random values in the range BTA uses
 * and checks byte by byte what ends up in the buffer (24-byte compressed format or the original 28-byte one).
 */
public class VertexPackTest {
  static int failures = 0;
  static void check(boolean ok, String m) { if (!ok && failures++ < 20) System.out.println("FAILED: " + m); }

  public static void main(String[] a) {
    boolean on = VertexPack.ON;
    TessellatorTerrainImpl t = new TessellatorTerrainImpl(0x100000);
    int stride = t.data.config.getVertexSize();
    check(stride == (on ? 24 : 28), "vertex size " + stride);
    Random r = new Random(7);
    double maxErrPos = 0, maxErrUV = 0;
    for (int testCase = 0; testCase < 2000; testCase++) {
      t.startDrawingQuads();
      double tx = -(r.nextInt(64) - 32) * 512.0, tz = -(r.nextInt(64) - 32) * 512.0; // region translation, as AsyncChunkRenderer does
      t.setTranslation(tx, 0.0, tz);
      int quads = 1 + r.nextInt(40);
      float[][] expected = new float[quads * 4][];
      for (int q = 0; q < quads; q++) {
        int col = r.nextInt(0x1000000);
        int nx = r.nextInt(3) - 1, ny = r.nextInt(3) - 1, nz = r.nextInt(3) - 1;
        t.setColor1i(col); t.setNormal(nx, ny, nz); t.setShade1i(r.nextInt(256)); t.setLightmapCoord1i(r.nextInt(256));
        for (int v = 0; v < 4; v++) {
          // position inside the region (0..512 in x/z, 0..256 in y) with a margin for models, sometimes on the 1/16 grid
          double lx = r.nextBoolean() ? r.nextInt(512 * 16) / 16.0 : r.nextDouble() * 514 - 1;
          double ly = r.nextBoolean() ? r.nextInt(256 * 16) / 16.0 : r.nextDouble() * 258 - 1;
          double lz = r.nextBoolean() ? r.nextInt(512 * 16) / 16.0 : r.nextDouble() * 514 - 1;
          double u = r.nextDouble(), vv = r.nextDouble();
          t.setTextureUV(u, vv);
          t.addVertex(lx - tx, ly, lz - tz);
          expected[q * 4 + v] = new float[]{(float) lx, (float) ly, (float) lz, (float) u, (float) vv};
        }
      }
      ByteBuffer b = t.data.getBuffer().duplicate().order(t.data.getBuffer().order());
      check(t.data.vertexCount == quads * 6, "vertexCount " + t.data.vertexCount + " != " + quads * 6);
      check(b.position() == quads * 6 * stride, "bytes written " + b.position() + " != " + quads * 6 * stride);
      // vertex order when turning a face into triangles: 0 1 2 | 0 2 3
      int[] order = {0, 1, 2, 0, 2, 3};
      for (int q = 0; q < quads; q++) {
        for (int k = 0; k < 6; k++) {
          int base = (q * 6 + k) * stride;
          float[] e = expected[q * 4 + order[k]];
          float x, y, z, u, v;
          if (on) {
            x = b.getFloat(base); y = b.getFloat(base + 4); z = b.getFloat(base + 8);
            u = VertexPack.decodeUV(b.getShort(base + 18)); v = VertexPack.decodeUV(b.getShort(base + 20));
          } else {
            x = b.getFloat(base); y = b.getFloat(base + 4); z = b.getFloat(base + 8); u = b.getFloat(base + 18); v = b.getFloat(base + 22);
          }
          double ep = Math.max(Math.abs(x - e[0]), Math.max(Math.abs(y - e[1]), Math.abs(z - e[2])));
          double eu = Math.max(Math.abs(u - e[3]), Math.abs(v - e[4]));
          maxErrPos = Math.max(maxErrPos, ep); maxErrUV = Math.max(maxErrUV, eu);
          // positions: bit-identical to the original format (not quantized)
          check(ep <= 1e-6, "position error " + ep); // 1e-6: quantizing to 1/1024 of a block (a discarded design) already fails // (bit-for-bit equality with the original format is checked in run-tests.sh)
          check(eu <= (on ? 0.5 / 32768 + 1e-7 : 1e-7), "UV error " + eu);
          // UV on the power-of-two atlas grid (k / 4096): exact
          if (on) { float g = Math.round(e[3] * 4096) / 4096f; check(VertexPack.decodeUV(VertexPack.encodeUV(g)) == g, "grid UV not exact"); }
        }
        // duplicated vertices are exact copies, byte for byte
        for (int[] par : new int[][]{{0, 3}, {2, 4}}) {
          int b1 = (q * 6 + par[0]) * stride, b2 = (q * 6 + par[1]) * stride;
          for (int i = 0; i < stride; i++) check(b.get(b1 + i) == b.get(b2 + i), "duplicated vertex differs");
        }
      }
      t.drawing = false;
    }
    check(VertexPack.clampedUV.get() == 0, "clamped values");
    System.out.printf("VertexPackTest [%s, %d bytes]: %s - max position error %.5f blocks, UV %.2e%n",
        on ? "compressed" : "original", stride, failures == 0 ? "OK" : failures + " FAILURES", maxErrPos, maxErrUV);
    if (failures > 0) System.exit(1);
  }
}
