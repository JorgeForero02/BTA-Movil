package net.betterthanadventure.btamobile.meshfix;

/** PlayerEdit: which sections count as changed by the player's last block action. */
public class PlayerEditTest {
  static int failures;
  static void check(boolean c, String what) { if (!c) { failures++; System.out.println("FAIL: " + what); } }

  public static void main(String[] a) {
    long t = 1_000_000_000L;
    check(!PlayerEdit.affects(0, 0, 0, 16, 16, 16, t), "no previous action affects nothing");
    PlayerEdit.set(5, 70, 5, t);
    check(PlayerEdit.affects(0, 64, 0, 16, 16, 16, t + 1_000_000), "the block's own section");
    check(!PlayerEdit.affects(16, 64, 0, 16, 16, 16, t + 1_000_000), "neighbour section far from the block (x=5) does not");
    check(!PlayerEdit.affects(0, 64, 0, 16, 16, 16, t + PlayerEdit.WINDOW_NS + 1), "outside the time window");
    check(!PlayerEdit.affects(0, 64, 0, 16, 16, 16, t - 1), "before the action");
    // block on a section border: the neighbours dirtied by RenderGlobal (x-1..x+1) and a placed block next to it
    PlayerEdit.set(15, 64, 0, t);
    check(PlayerEdit.affects(16, 64, 0, 16, 16, 16, t), "x border: section next door");
    check(PlayerEdit.affects(0, 48, 0, 16, 16, 16, t), "y border: section below");
    check(PlayerEdit.affects(0, 64, -16, 16, 16, 16, t), "z border: section behind");
    check(PlayerEdit.affects(16, 48, -16, 16, 16, 16, t), "corner: diagonal");
    check(!PlayerEdit.affects(32, 64, 0, 16, 16, 16, t), "two sections away: no");
    check(!PlayerEdit.affects(0, 80, 0, 16, 16, 16, t), "above (y=64 is 2 from 80): no");
    PlayerEdit.set(-1, 64, -1, t);
    check(PlayerEdit.affects(-16, 64, -16, 16, 16, 16, t) && PlayerEdit.affects(0, 64, 0, 16, 16, 16, t), "negative coordinates");
    PlayerEdit.clear();
    check(!PlayerEdit.affects(-16, 64, -16, 16, 16, 16, t), "clear forgets the action");
    System.out.println("PlayerEdit: " + (failures == 0 ? "OK (sections affected by breaking/placing, borders, corners and time window)" : failures + " FAILURES"));
    if (failures > 0) System.exit(1);
  }
}
