package net.betterthanadventure.btamobile.meshfix;

/** Profiler.saveLimit: chunks saved per tick by time budget (1..5), simulating the save loop of
 *  ChunkProviderThreaded.tick: "i++; save; if (i <= saveLimit(i, loaded)) continue; else break". */
public class SaveBudgetTest {
  // Fake clock: a save costs exactly ns, so the result never depends on how busy the machine is
  // (a busy-wait on the real clock failed 1 run in 4 on the 2.5 ms and 4 ms cases, which sit on the budget's edge).
  static long now;
  static void busy(long ns) { now += ns; }

  static int tick(long costNs) {
    int i = 0;
    while (true) {
      i++;
      Profiler.saveBegin();
      busy(costNs); // saveChunk + onUnload
      if (!(i <= Profiler.saveLimit(i, null))) break;
      if (i > 10) break;
    }
    return i;
  }

  static int steady(long costNs) { int n = 0; for (int k = 0; k < 8; k++) n = tick(costNs); return n; }

  public static void main(String[] a) {
    Profiler.clock = () -> now;
    if (Integer.getInteger("bta.saveBudgetMs") == null) { // default: fixed 2 per tick, 5 with many chunks to spare
      java.util.Map<Object, Object> many = new java.util.HashMap<>();
      for (int k = 0; k < 5000; k++) many.put(k, k);
      int d = steady(500_000L), dm = 0;
      while (true) { dm++; Profiler.saveBegin(); if (!(dm <= Profiler.saveLimit(dm, many))) break; }
      boolean ok = d == 2 && dm == 5;
      System.out.println("Save budget (default): " + (ok ? "OK (2 per tick; 5 with many spare chunks)" : "FAIL " + d + "/" + dm));
      if (!ok) System.exit(1);
      return;
    }
    int fast = steady(500_000L), mid = steady(2_500_000L), phone = steady(4_000_000L), slow = steady(12_000_000L);
    System.out.println("Saves per tick: 0.5 ms/chunk -> " + fast + ", 2.5 ms -> " + mid + ", 4 ms -> " + phone + ", 12 ms -> " + slow);
    java.util.Map<Object, Object> many = new java.util.HashMap<>();
    for (int k = 0; k < 5000; k++) many.put(k, k);
    int i = 0; while (true) { i++; Profiler.saveBegin(); busy(4_000_000L); if (!(i <= Profiler.saveLimit(i, many))) break; }
    boolean ok = fast == 5 && phone == 2 && slow == 1 && mid == 3 && i == 5;
    System.out.println("Save budget: " + (ok ? "OK (time budget, minimum 1, maximum 5; with many spare chunks, BTA's 5)" : "FAIL (expected 5/3/2/1 and 5 with many chunks; got " + fast + "/" + mid + "/" + phone + "/" + slow + " and " + i + ")"));
    if (!ok) System.exit(1);
  }
}
