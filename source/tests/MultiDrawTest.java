import java.util.*;
import net.betterthanadventure.btamobile.zalithfix.MultiDraw;

/** MultiDraw.merge: the same vertices are drawn, none repeated or lost, merges are correct. */
public class MultiDrawTest {
  static int failures = 0;
  static void check(boolean ok, String msg) { if (!ok) { failures++; System.out.println("FAILED: " + msg); } }

  public static void main(String[] a) {
    Random r = new Random(1234);
    for (int testCase = 0; testCase < 20000; testCase++) {
      // Simulates a VertexBuffer: non-overlapping ranges, sometimes adjacent, sometimes with gaps, some empty.
      int n = r.nextInt(300);
      int[] first = new int[n], count = new int[n];
      int pos = r.nextInt(50);
      for (int i = 0; i < n; i++) {
        if (r.nextInt(3) == 0) pos += r.nextInt(40) + 1; // gap (invisible or deleted section)
        first[i] = pos;
        count[i] = r.nextInt(8) == 0 ? 0 : (r.nextInt(200) + 1) * 3;
        pos += count[i];
      }
      // Arbitrary order, like the HashSet in RenderList
      for (int i = n - 1; i > 0; i--) { int j = r.nextInt(i + 1); int t = first[i]; first[i] = first[j]; first[j] = t; t = count[i]; count[i] = count[j]; count[j] = t; }
      int[] of = new int[n], oc = new int[n];
      int m = MultiDraw.merge(first, count, n, of, oc);

      // 1) same set of vertices, none repeated
      BitSet expected = new BitSet(), actual = new BitSet();
      long expectedTotal = 0, actualTotal = 0;
      for (int i = 0; i < n; i++) { expected.set(first[i], first[i] + count[i]); expectedTotal += count[i]; }
      for (int i = 0; i < m; i++) { check(oc[i] > 0, "empty range in the output"); actual.set(of[i], of[i] + oc[i]); actualTotal += oc[i]; }
      check(expected.equals(actual), "case " + testCase + ": different set of vertices");
      check(expectedTotal == actualTotal, "case " + testCase + ": vertices repeated or lost");
      // 2) output sorted, with no touching ranges (maximal merges)
      for (int i = 1; i < m; i++) check(of[i - 1] + oc[i - 1] < of[i], "case " + testCase + ": touching ranges left unmerged, or out of order");
      // 3) every output range keeps multiples of 3 (whole triangles)
      for (int i = 0; i < m; i++) check(oc[i] % 3 == 0 && of[i] % 1 == 0, "split triangle");
    }
    // Overlaps (BTA does not produce them): never merged, nothing is lost
    int[] f = {0, 5, 10}, c = {9, 9, 3}, of = new int[3], oc = new int[3];
    int m = MultiDraw.merge(f, c, 3, of, oc);
    check(m == 3, "overlaps must not be merged (m=" + m + ")");
    // Empty
    check(MultiDraw.merge(new int[0], new int[0], 0, new int[0], new int[0]) == 0, "empty list");
    // Fully contiguous -> a single call
    int[] f2 = {30, 0, 12}, c2 = {6, 12, 18}, of2 = new int[3], oc2 = new int[3];
    check(MultiDraw.merge(f2, c2, 3, of2, oc2) == 1 && of2[0] == 0 && oc2[0] == 36, "fully contiguous");

    System.out.println(failures == 0 ? "MultiDrawTest: OK (20000 random cases + edges)" : "MultiDrawTest: " + failures + " failures");
    if (failures > 0) System.exit(1);
  }
}
