import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.betterthanadventure.btamobile.meshfix.Mesher;

/** Worker pool: every job runs exactly once, on a worker thread, and comes back through COMPLETED. */
public class MesherTest {
  public static void main(String[] a) throws Exception {
    int total = 50_000;
    Integer requested = Integer.getInteger("bta.asyncMesh.threads");
    AtomicInteger runs = new AtomicInteger(), offWorker = new AtomicInteger();
    Set<String> threads = ConcurrentHashMap.newKeySet();
    Set<Mesher.Task> submitted = Collections.newSetFromMap(new IdentityHashMap<>());
    int submittedCount = 0, completed = 0;
    long deadline = System.currentTimeMillis() + 60_000;
    while (completed < total && System.currentTimeMillis() < deadline) {
      while (submittedCount < total) {
        boolean prio = submittedCount % 7 == 0;
        Mesher.Task t = () -> { runs.incrementAndGet(); threads.add(Thread.currentThread().getName()); if (!Mesher.isWorker()) offWorker.incrementAndGet(); };
        if (!Mesher.submit(t, prio)) break;
        submitted.add(t); submittedCount++;
      }
      Mesher.Task c;
      while ((c = Mesher.COMPLETED.poll()) != null) { if (!submitted.remove(c)) { System.out.println("FAILED: unknown or repeated completion"); System.exit(1); } completed++; }
      Thread.onSpinWait();
    }
    boolean ok = completed == total && runs.get() == total && offWorker.get() == 0 && submitted.isEmpty()
        && Mesher.inFlight() == 0 && threads.size() <= Mesher.threads() && !Mesher.isWorker()
        && (requested == null || Mesher.threads() == requested); // -Dbta.asyncMesh.threads=N gives N workers, uncapped
    System.out.println((ok ? "MesherTest: OK" : "MesherTest: FAILED") + " - " + completed + " jobs, " + Mesher.threads() + " workers " + new TreeSet<>(threads));
    if (!ok) System.exit(1);
    System.exit(0); // the workers are daemons
  }
}
