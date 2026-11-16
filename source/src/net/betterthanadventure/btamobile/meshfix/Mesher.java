package net.betterthanadventure.btamobile.meshfix;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background chunk meshing for BTA. Worker threads build chunk geometry; the main thread
 * only takes the chunk snapshot (ChunkCache) and uploads finished vertex data.
 * Several workers are safe: every piece of scratch state a block model writes while drawing is per
 * thread (MesherRenderBlocks: RenderBlocks, dragonfly lighting cache and vectors, generic queryPos,
 * fluid vectors) and each job has its own snapshot and tessellator. Checked with a call graph of
 * everything a job can reach.
 * Workers: option "Chunk Building Threads" (Video > BTA Movil), applied at once, no restart:
 *  - Auto (default): Sodium's formula, clamp(max(cores / 3, cores - 6), 1, 10), capped below: 2 on an
 *    8-core phone, 1 on a 4-core one;
 *  - 1 .. maxAllowed(), where maxAllowed() = min(4, cores - 2): at least 2 cores always stay free for
 *    the game's main thread and the GPU driver / Android.
 * -Dbta.asyncMesh.threads=N (1..4) fixes the number and ignores the option.
 * Priority jobs (chunks near the player) go to a separate FIFO queue the workers serve first.
 * Disable with the option "Background Chunk Building (restart)" or -Dbta.asyncMesh=false (BTA's original
 * synchronous rebuild is used; Cave Culling needs it, so it goes off too).
 */
public final class Mesher {
  /** Option "Background Chunk Building (restart)"; -Dbta.asyncMesh=false/true forces it.
   *  Read once, at the first call (a world is loading: options.txt and MobileOptions are ready). Not a
   *  static final field: MobileOptions' own initialization calls Mesher.maxAllowed(), so reading the option
   *  while Mesher initializes could see MobileOptions half-built and ignore the saved value. */
  public static boolean enabled() {
    Boolean value = enabled;
    if (value == null) enabled = value = OptionsSafe.switchOn("bta.asyncMesh", "asyncMesh");
    return value;
  }
  private static volatile Boolean enabled;
  private static final Integer IN_FLIGHT_FIXED = Integer.getInteger("bta.asyncMesh.inflight");
  private static final Integer THREADS_FIXED = fixedThreads();
  /** Cores the JVM sees (big and little cores alike). */
  public static final int CORES = Math.max(1, Runtime.getRuntime().availableProcessors());

  private static Integer fixedThreads() {
    String requested = System.getProperty("bta.asyncMesh.threads");
    if (requested == null) return null;
    try { return Math.max(1, Math.min(4, Integer.parseInt(requested.trim()))); } catch (NumberFormatException ignored) { return null; }
  }

  /** Highest value the option offers: up to 4, always leaving 2 cores free. */
  public static int maxAllowed() { return maxAllowed(CORES); }
  static int maxAllowed(int cores) { return Math.max(1, Math.min(4, cores - 2)); }

  /** "Auto": Sodium's default (ChunkBuilder.getOptimalThreadCount), within maxAllowed. */
  public static int autoThreads() { return autoThreads(CORES); }
  static int autoThreads(int cores) { return Math.max(1, Math.min(maxAllowed(cores), Math.max(cores / 3, cores - 6))); }

  /** Option value (0 = Auto, else 1..maxAllowed) -> workers to run. */
  public static int resolve(int option) { return option <= 0 ? autoThreads() : Math.min(option, maxAllowed()); }

  private static int wanted() {
    if (THREADS_FIXED != null) return THREADS_FIXED;
    return resolve(OptionsSafe.getInt("meshThreads", 0));
  }

  /** True if -Dbta.asyncMesh.threads fixes the number (the option is then ignored). */
  public static boolean fixed() { return THREADS_FIXED != null; }

  /** Workers that should be running now (changes when the option changes). */
  private static volatile int target = wanted();
  public static int threads() { return target; }

  /** Jobs allowed queued or running: 8 per worker, at least 16 (-Dbta.asyncMesh.inflight=N fixes it). */
  public static int maxInFlight() { return IN_FLIGHT_FIXED != null ? Math.max(1, IN_FLIGHT_FIXED) : Math.max(16, 8 * target); }

  /** Implemented by jobs; {@link #run()} executes on the worker. */
  public interface Task extends Runnable {}

  private static final ConcurrentLinkedQueue<Task> PRIORITY = new ConcurrentLinkedQueue<>();
  private static final ConcurrentLinkedQueue<Task> NORMAL = new ConcurrentLinkedQueue<>();
  private static final Semaphore AVAILABLE = new Semaphore(0);
  /** Finished jobs, drained by the main thread. */
  public static final ConcurrentLinkedQueue<Task> COMPLETED = new ConcurrentLinkedQueue<>();
  private static final AtomicInteger inFlight = new AtomicInteger();
  /** Running workers by index (main thread creates them; a worker clears its own slot when it retires). */
  private static final WorkerThread[] workers = new WorkerThread[4];
  private static boolean started, changed; // changed: setThreads was called (the option changed), it wins

  /** Marker type so shared render state can tell the worker apart from the main thread. */
  public static final class WorkerThread extends Thread {
    public final int index;
    WorkerThread(int workerIndex) {
      super("BTA-Mesher-" + workerIndex);
      index = workerIndex;
      setDaemon(true);
      setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
    }
    @Override public void run() {
      try {
        while (true) {
          try { AVAILABLE.acquire(); } catch (InterruptedException ignored) { continue; }
          if (index >= target) { AVAILABLE.release(); return; } // fewer workers wanted: hand the job to another and stop
          Task task = PRIORITY.poll();
          if (task == null) task = NORMAL.poll();
          if (task == null) continue;
          try { task.run(); }
          catch (Throwable error) { reportError(error); }
          finally { inFlight.decrementAndGet(); COMPLETED.add(task); }
        }
      } finally {
        synchronized (Mesher.class) { if (workers[index] == this) workers[index] = null; }
      }
    }
  }

  public static boolean isWorker() { return Thread.currentThread() instanceof WorkerThread; }

  /** Jobs queued or running. */
  public static int inFlight() { return inFlight.get(); }

  /** Main thread: whether {@link #submit} would accept a job right now. */
  public static boolean canAccept(boolean priority) { return priority || inFlight.get() < maxInFlight(); }

  /** Starts the workers still missing for the current target (a retiring worker with the same index
   *  may still be waiting for a job: it leaves on its own, the new one takes its slot). */
  private static void ensureWorkers() {
    synchronized (Mesher.class) {
      // first world: read the option now (this class may have been loaded before options.txt was read)
      if (!started) { try { MobileOptions.clampMeshThreads(); } catch (Throwable ignored) { } } // first, so label and workers agree (no options system in tests)
      if (!started && !changed) target = wanted();
      int count = target;
      for (int i = 0; i < count; i++) {
        WorkerThread worker = workers[i];
        if (worker == null || !worker.isAlive()) { worker = new WorkerThread(i); workers[i] = worker; worker.start(); }
      }
      if (!started) {
        started = true;
        System.out.println("[BTA-mesh] async chunk meshing ON (" + count + (count == 1 ? " worker" : " workers") + ", " + CORES + " cores, max "
            + maxInFlight() + " jobs in flight)" + (THREADS_FIXED != null ? " [-Dbta.asyncMesh.threads]" : ""));
      }
    }
  }

  /** Option changed (main thread): more workers start now, extra ones stop after their current job. */
  public static void setThreads(int option) {
    if (THREADS_FIXED != null) return;
    int newTarget = resolve(option);
    changed = true;
    if (newTarget == target) return;
    int previous = target;
    target = newTarget;
    if (newTarget < previous) AVAILABLE.release(previous - newTarget); // wake the extra workers so they can leave even with no work
    if (started) { ensureWorkers(); System.out.println("[BTA-mesh] chunk building threads: " + previous + " -> " + newTarget); }
  }

  /** Main thread. Returns false if too many jobs are queued; the caller retries next frame. */
  public static boolean submit(Task task, boolean priority) {
    ensureWorkers();
    if (!canAccept(priority)) return false;
    inFlight.incrementAndGet();
    (priority ? PRIORITY : NORMAL).add(task);
    AVAILABLE.release();
    return true;
  }

  private static final AtomicInteger errors = new AtomicInteger();
  public static void reportError(Throwable error) {
    int count = errors.incrementAndGet();
    if (count <= 3) { System.out.println("[BTA-mesh] chunk mesh failed (will retry, then fall back to the main thread):"); error.printStackTrace(System.out); }
    else if (count == 4) System.out.println("[BTA-mesh] (further mesh errors are not printed)");
  }
}
