package net.betterthanadventure.btamobile.meshfix;

import java.util.*;
import java.util.concurrent.*;

/** "Chunk Building Threads": Auto / maximum per core count, and live resizing of the worker pool. */
public class ThreadCountTest {
  static int failures;
  static void check(boolean c, String w) { if (!c) { failures++; System.out.println("FAIL: " + w); } }

  /** Submits n jobs and waits for all; returns the worker names that ran them. */
  static Set<String> run(int n) throws Exception {
    Set<String> names = ConcurrentHashMap.newKeySet();
    Set<Mesher.Task> sent = Collections.newSetFromMap(new IdentityHashMap<>());
    int s = 0, done = 0; long end = System.currentTimeMillis() + 30_000;
    while (done < n && System.currentTimeMillis() < end) {
      while (s < n) {
        Mesher.Task t = () -> { names.add(Thread.currentThread().getName()); long e = System.nanoTime() + 20_000; while (System.nanoTime() < e) { } };
        if (!Mesher.submit(t, false)) break;
        sent.add(t); s++;
      }
      Mesher.Task c; while ((c = Mesher.COMPLETED.poll()) != null) { check(sent.remove(c), "completed twice"); done++; }
      Thread.onSpinWait();
    }
    check(done == n && Mesher.inFlight() == 0, "not all finished (" + done + "/" + n + ")");
    return names;
  }

  public static void main(String[] a) throws Exception {
    // cores -> (max, auto): 2 cores always free, at most 4; Auto = Sodium's clamp(max(c/3, c-6), 1, 10) within the max
    int[][] t = {{1, 1, 1}, {2, 1, 1}, {4, 2, 1}, {6, 4, 2}, {8, 4, 2}, {10, 4, 4}, {12, 4, 4}};
    for (int[] r : t) {
      check(Mesher.maxAllowed(r[0]) == r[1], r[0] + " cores: max " + Mesher.maxAllowed(r[0]) + " (expected " + r[1] + ")");
      check(Mesher.autoThreads(r[0]) == r[2], r[0] + " cores: auto " + Mesher.autoThreads(r[0]) + " (expected " + r[2] + ")");
    }
    int max = Mesher.maxAllowed();
    check(Mesher.resolve(99) == max && Mesher.resolve(0) == Mesher.autoThreads(), "resolve out of range");
    Set<String> n1, n2, n3;
    Mesher.setThreads(1); n1 = run(4000);
    Mesher.setThreads(max); n2 = run(20000);
    Mesher.setThreads(1); run(200); Thread.sleep(300); n3 = run(4000);
    check(n1.equals(Set.of("BTA-Mesher-0")), "with 1 thread these worked: " + n1);
    check(n2.size() <= max && n2.contains("BTA-Mesher-0"), "with " + max + " threads these worked: " + n2);
    check(n3.equals(Set.of("BTA-Mesher-0")), "after going back to 1 these kept working: " + n3);
    long alive = Thread.getAllStackTraces().keySet().stream().filter(x -> x.getName().startsWith("BTA-Mesher-")).count();
    System.out.println("ThreadCountTest: " + (failures == 0 ? "OK" : failures + " FAILURES") + " - max/auto per core count, live change 1 -> " + max + " -> 1 (worked " + n2 + "; alive at the end " + alive + ")");
    if (failures > 0) System.exit(1);
    System.exit(0);
  }
}
