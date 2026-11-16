import java.util.*;
import net.betterthanadventure.btamobile.zalithfix.MultiDraw;

/** MultiDraw.contactRanges (water contact faces): the contact faces of each range come out separately and first;
 *  between the two lists exactly the same vertices are drawn, none repeated or lost. */
public class FluidContactTest {
  public static void main(String[] a) {
    Random r = new Random(77);
    int failures = 0;
    for (int testCase = 0; testCase < 20000; testCase++) {
      int n = r.nextInt(200);
      int[] first = new int[n], count = new int[n];
      Map<Integer, Integer> k = new HashMap<>();
      int pos = r.nextInt(30);
      for (int i = 0; i < n; i++) {
        if (r.nextBoolean()) pos += r.nextInt(30) + 1;
        first[i] = pos; count[i] = r.nextInt(8) == 0 ? 0 : (r.nextInt(100) + 1) * 3; pos += count[i];
        if (r.nextInt(4) == 0) k.put(first[i], r.nextInt(5) == 0 ? count[i] + 30 : r.nextInt(count[i] + 1)); // sometimes more than the range
      }
      int[] aF = new int[n], aC = new int[n], bF = new int[n], bC = new int[n], s = new int[2];
      boolean hit = MultiDraw.contactRanges(first, count, n, (f, c) -> k.getOrDefault(f, 0), aF, aC, bF, bC, s);
      boolean expectHit = false;
      for (int i = 0; i < n; i++) expectHit |= count[i] > 0 && k.getOrDefault(first[i], 0) > 0;
      if (hit != expectHit) { failures++; continue; }
      if (!hit) continue;
      BitSet expected = new BitSet(), actual = new BitSet();
      long total = 0, drawn = 0;
      for (int i = 0; i < n; i++) { expected.set(first[i], first[i] + count[i]); total += count[i]; }
      for (int i = 0; i < s[0]; i++) {
        actual.set(aF[i], aF[i] + aC[i]); drawn += aC[i];
        int j = Arrays.binarySearch(sortedFirsts(first, n), aF[i]);
        if (j < 0 || aC[i] <= 0) failures++; // each contact piece starts where its range starts
      }
      for (int i = 0; i < s[1]; i++) { if (bC[i] <= 0) failures++; actual.set(bF[i], bF[i] + bC[i]); drawn += bC[i]; }
      if (!expected.equals(actual) || drawn != total) failures++;
    }
    failures += table();
    System.out.println(failures == 0 ? "FluidContactTest: OK (20000 cases: contact faces separate and first, same vertices; range table = HashMap over 200000 operations)" : "FluidContactTest: " + failures + " FAILURES");
    if (failures != 0) System.exit(1);
  }
  static int[] sortedFirsts(int[] f, int n) { int[] c = Arrays.copyOf(f, n); Arrays.sort(c); return c; }

  /** The FluidContact table (open addressing, delete with reinsertion, growth) against a HashMap. */
  static int table() {
    try {
      Class<?> c = net.betterthanadventure.btamobile.meshfix.FluidContact.class;
      java.lang.reflect.Method put = c.getDeclaredMethod("put", long.class, long.class, Object.class), rem = c.getDeclaredMethod("remove", long.class);
      put.setAccessible(true); rem.setAccessible(true);
      net.betterthanadventure.btamobile.meshfix.FluidContact.clear();
      java.util.Map<Integer, long[]> ref = new java.util.HashMap<>();
      java.util.Random r = new java.util.Random(7);
      int bad = 0;
      for (int op = 0; op < 200000; op++) {
        int first = r.nextInt(op < 100000 ? 3000 : 64) * 4; // many collisions in the second half
        int q = r.nextInt(10);
        if (q < 5) { int verts = 4 + r.nextInt(50) * 4, k = 1 + r.nextInt(verts); put.invoke(null, (long) first, ((long) verts << 32) | k, null); ref.put(first, new long[]{verts, k}); }
        else if (q < 8) { rem.invoke(null, (long) first); ref.remove(first); }
        else if (q == 8 && r.nextInt(5000) == 0) { net.betterthanadventure.btamobile.meshfix.FluidContact.clear(); ref.clear(); }
        int probe = r.nextInt(3000) * 4;
        long[] e = ref.get(probe);
        int expected = e == null ? 0 : (int) e[1];
        int seen = net.betterthanadventure.btamobile.meshfix.FluidContact.at(probe, e == null ? 8 : (int) e[0]);
        if (seen != expected) bad++;
        if (e != null && net.betterthanadventure.btamobile.meshfix.FluidContact.at(probe, (int) e[0] + 4) != 0) bad++; // a different count: not that range
      }
      net.betterthanadventure.btamobile.meshfix.FluidContact.clear();
      return bad;
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }
}
