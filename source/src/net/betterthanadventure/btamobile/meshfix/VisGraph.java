package net.betterthanadventure.btamobile.meshfix;

/**
 * Section connectivity for cave culling (same idea as Minecraft 1.8+ VisGraph / Sodium).
 * Input: which of the 16x16x16 cells are opaque (index x | z<<4 | y<<8).
 * Output: a 36-bit set, bit (from*6 + to) = "you can get from face 'from' to face 'to' through air".
 * Faces: 0 DOWN(-y) 1 UP(+y) 2 NORTH(-z) 3 SOUTH(+z) 4 WEST(-x) 5 EAST(+x); opposite(d) = d ^ 1.
 */
public final class VisGraph {
  public static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
  public static final long ALL = (1L << 36) - 1;
  public static final long NONE = 0L;
  private static final int[] EDGE = edges();

  private static int[] edges() {
    int[] cells = new int[1352];
    int count = 0;
    for (int y = 0; y < 16; y++)
      for (int z = 0; z < 16; z++)
        for (int x = 0; x < 16; x++)
          if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15) cells[count++] = x | z << 4 | y << 8;
    return cells;
  }

  /** opaque: 64 longs (4096 bits). Not modified. */
  public static long compute(long[] opaque) {
    int count = 0;
    for (long word : opaque) count += Long.bitCount(word);
    if (count < 256) return ALL;    // cannot seal anything (vanilla/Sodium threshold)
    if (count == 4096) return NONE;
    long[] seen = opaque.clone();   // opaque cells count as already visited
    int[] queue = new int[4096];
    long result = 0;
    for (int start : EDGE) {
      if ((seen[start >>> 6] & (1L << start)) != 0) continue;
      int faces = 0, head = 0, tail = 0;
      seen[start >>> 6] |= 1L << start;
      queue[tail++] = start;
      while (head < tail) {
        int cell = queue[head++];
        int x = cell & 15, z = (cell >>> 4) & 15, y = cell >>> 8;
        if (x == 0) faces |= 1 << WEST; else if (x == 15) faces |= 1 << EAST;
        if (y == 0) faces |= 1 << DOWN; else if (y == 15) faces |= 1 << UP;
        if (z == 0) faces |= 1 << NORTH; else if (z == 15) faces |= 1 << SOUTH;
        if (x > 0) tail = push(seen, queue, tail, cell - 1);
        if (x < 15) tail = push(seen, queue, tail, cell + 1);
        if (z > 0) tail = push(seen, queue, tail, cell - 16);
        if (z < 15) tail = push(seen, queue, tail, cell + 16);
        if (y > 0) tail = push(seen, queue, tail, cell - 256);
        if (y < 15) tail = push(seen, queue, tail, cell + 256);
      }
      for (int from = 0; from < 6; from++)
        if ((faces & (1 << from)) != 0)
          for (int to = 0; to < 6; to++)
            if ((faces & (1 << to)) != 0) result |= 1L << (from * 6 + to);
      if (result == ALL) break;
    }
    return result;
  }

  private static int push(long[] seen, int[] queue, int tail, int cell) {
    long bit = 1L << cell;
    if ((seen[cell >>> 6] & bit) != 0) return tail;
    seen[cell >>> 6] |= bit;
    queue[tail] = cell;
    return tail + 1;
  }

  /** Faces reachable from any face in 'incoming' (6-bit mask). */
  public static int connections(long vis, int incoming) {
    int reachable = 0;
    for (int face = 0; face < 6; face++)
      if ((incoming & (1 << face)) != 0) reachable |= (int) ((vis >>> (face * 6)) & 0x3F);
    return reachable;
  }

  public static boolean connected(long vis, int from, int to) {
    return ((vis >>> (from * 6 + to)) & 1L) != 0;
  }
}
