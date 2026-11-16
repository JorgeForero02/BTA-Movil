package net.betterthanadventure.btamobile.zalithfix;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
/** The plan cache is invalidated when RenderList changes the contents of its buffers. */
public class MultiDrawCacheTest {
  public static void main(String[] a) {
    IntBuffer f = BufferUtils.createIntBuffer(8), c = BufferUtils.createIntBuffer(8);
    f.put(new int[]{0, 6}).flip(); c.put(new int[]{6, 6}).flip();
    MultiDraw.Plan p1 = MultiDraw.plan(f, c);
    boolean ok = p1.outRangeCount == 1 && p1.outCount[0] == 12;
    MultiDraw.Plan p2 = MultiDraw.plan(f, c);           // same contents -> same plan, not recomputed
    ok &= p2 == p1 && p2.outRangeCount == 1;
    f.clear(); c.clear(); f.put(new int[]{0, 100}).flip(); c.put(new int[]{6, 6}).flip(); // contents change
    MultiDraw.Plan p3 = MultiDraw.plan(f, c);
    ok &= p3.outRangeCount == 2 && p3.outFirst[1] == 100;
    f.clear(); c.clear(); f.put(new int[]{0, 6, 12}).flip(); c.put(new int[]{6, 6, 0}).flip(); // size changes, empty range at the end
    MultiDraw.Plan p4 = MultiDraw.plan(f, c);
    ok &= p4.outRangeCount == 1 && p4.outCount[0] == 12;
    System.out.println(ok ? "MultiDrawCacheTest: OK" : "MultiDrawCacheTest: FAIL");
    if (!ok) System.exit(1);
  }
}
