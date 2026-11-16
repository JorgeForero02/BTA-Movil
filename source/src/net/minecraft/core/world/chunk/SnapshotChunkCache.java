package net.minecraft.core.world.chunk;

import net.betterthanadventure.btamobile.meshfix.Mesher;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.block.entity.TileEntity;
import net.minecraft.core.world.World;
import net.minecraft.core.world.biome.Biome;
import net.minecraft.core.world.biome.Biomes;
import net.minecraft.core.world.pos.ChunkPos;
import net.minecraft.core.world.pos.ChunkTilePos;
import net.minecraft.core.world.pos.TilePos;
import net.minecraft.core.world.pos.TilePosc;

/**
 * ChunkCache used by the background mesher. On the worker it never calls into World (the chunk
 * provider and biome generator are not thread-safe) and never creates tile entities:
 *  - climate/biome come from the captured chunks; if a chunk has no cached value a neutral default
 *    is used and {@link #missingClimate} is set, so the main thread can fill the values and re-mesh.
 *  - getTileEntity is a read-only lookup in a COPY of the chunks' tile entity maps (see below).
 * On the main thread it behaves exactly like ChunkCache (live chunk maps).
 * Note: the blocks are LIVE chunk references, not a copy. The main thread may change
 * blocks while the worker reads them; that is safe because every block change bumps the renderer's
 * generation and the chunk is meshed again, so a mixed result is never kept.
 * The tile entity maps ARE copied (-Dbta.tileSnapshot=false turns it off): the constructor
 * (main thread) copies the entries inside the cached area, so the worker never walks a live HashMap
 * that the main thread may be resizing. The copy is shallow: the TileEntity objects are the live ones.
 */
public class SnapshotChunkCache extends ChunkCache {
  /** -Dbta.tileSnapshot=false: the worker reads the live tile entity maps, as before. Read once. */
  private static final boolean TILE_SNAPSHOT = !"false".equalsIgnoreCase(System.getProperty("bta.tileSnapshot"));

  private final World snapWorld;
  /** Tile entities inside the cached area, keyed by absolute position; null when there are none (or the switch is off). */
  private final Map<Long, TileEntity> tileCopy;
  public volatile boolean missingClimate;

  public SnapshotChunkCache(World world, TilePos min, TilePos max, boolean priority) {
    super(world, min, max, priority);
    this.snapWorld = world;
    this.tileCopy = TILE_SNAPSHOT ? copyTileEntities(world, min, max, priority) : null;
  }

  private static long key(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
  }

  /** Main thread. Copies (shallow) the tile entities of every chunk whose position is inside min..max, inclusive. */
  private static Map<Long, TileEntity> copyTileEntities(World world, TilePos min, TilePos max, boolean priority) {
    Map<Long, TileEntity> copy = null;
    for (int cx = min.x >> 4; cx <= max.x >> 4; cx++) {
      for (int cz = min.z >> 4; cz <= max.z >> 4; cz++) {
        Chunk chunk = world.getChunk(new ChunkPos(cx, cz), priority); // the chunk the superclass constructor just fetched
        if (chunk == null || chunk.tileEntityMap == null || chunk.tileEntityMap.isEmpty()) continue;
        for (Map.Entry<ChunkTilePos, TileEntity> entry : chunk.tileEntityMap.entrySet()) {
          int x = (cx << 4) + entry.getKey().x, y = entry.getKey().y, z = (cz << 4) + entry.getKey().z;
          if (x < min.x || x > max.x || y < min.y || y > max.y || z < min.z || z > max.z) continue;
          if (copy == null) copy = new HashMap<>();
          copy.put(key(x, y, z), entry.getValue());
        }
      }
    }
    return copy;
  }

  private Chunk chunkAt(TilePosc tilePos) {
    if (tilePos.y() < 0 || tilePos.y() >= getHeightBlocks()) return null;
    if (!isChunkLoaded(tilePos)) return null;
    return getChunk(tilePos);
  }

  private double missing(double fallback) { missingClimate = true; return fallback; }

  @Override
  public double getBlockTemperature(TilePosc tilePos) {
    if (!Mesher.isWorker()) return snapWorld.getBlockTemperature(tilePos);
    Chunk chunk = chunkAt(tilePos);
    if (chunk != null) { double value = chunk.getBlockTemperature(tilePos.x() & 15, tilePos.z() & 15); if (value != Double.NEGATIVE_INFINITY) return value; }
    return missing(0.5);
  }

  @Override
  public double getBlockHumidity(TilePosc tilePos) {
    if (!Mesher.isWorker()) return snapWorld.getBlockHumidity(tilePos);
    Chunk chunk = chunkAt(tilePos);
    if (chunk != null) { double value = chunk.getBlockHumidity(tilePos.x() & 15, tilePos.z() & 15); if (value != Double.NEGATIVE_INFINITY) return value; }
    return missing(0.5);
  }

  @Override
  public double getBlockVariety(TilePosc tilePos) {
    if (!Mesher.isWorker()) return snapWorld.getBlockVariety(tilePos);
    Chunk chunk = chunkAt(tilePos);
    if (chunk != null) { double value = chunk.getBlockVariety(tilePos.x() & 15, tilePos.z() & 15); if (value != Double.NEGATIVE_INFINITY) return value; }
    return missing(0.5);
  }

  @Override
  public Biome getBlockBiome(TilePosc tilePos) {
    if (!Mesher.isWorker()) return snapWorld.getBlockBiome(tilePos);
    Chunk chunk = chunkAt(tilePos);
    if (chunk != null) { Biome biome = chunk.getBlockBiome(tilePos.x() & 15, tilePos.y(), tilePos.z() & 15); if (biome != null) return biome; }
    missingClimate = true;
    return Biomes.OVERWORLD_PLAINS;
  }

  @Override
  public TileEntity getTileEntity(TilePosc tilePos) {
    if (!Mesher.isWorker()) return super.getTileEntity(tilePos);
    Chunk chunk = chunkAt(tilePos);
    if (chunk == null) return null;
    if (TILE_SNAPSHOT) {
      TileEntity tileEntity = tileCopy == null ? null : tileCopy.get(key(tilePos.x(), tilePos.y(), tilePos.z()));
      return tileEntity == null || tileEntity.isInvalid() ? null : tileEntity;
    }
    try {
      TileEntity tileEntity = chunk.tileEntityMap.get(new ChunkTilePos(tilePos.x() & 15, tilePos.y(), tilePos.z() & 15));
      return tileEntity == null || tileEntity.isInvalid() ? null : tileEntity;
    } catch (RuntimeException ignored) {
      return null; // map modified concurrently by the main thread; harmless for meshing
    }
  }

  /** Main thread: let World compute/cache climate and biome for every column/block of the cached area. */
  public void fillClimate(int x0, int y0, int z0, int x1, int y1, int z1) {
    TilePos cursor = new TilePos(0, 0, 0);
    int heightBlocks = getHeightBlocks();
    for (cursor.x = x0; cursor.x < x1; cursor.x++) {
      for (cursor.z = z0; cursor.z < z1; cursor.z++) {
        cursor.y = Math.max(0, Math.min(heightBlocks - 1, y0));
        snapWorld.getBlockTemperature(cursor);
        snapWorld.getBlockHumidity(cursor);
        snapWorld.getBlockVariety(cursor);
        for (cursor.y = Math.max(0, y0); cursor.y < Math.min(heightBlocks, y1); cursor.y++) snapWorld.getBlockBiome(cursor);
      }
    }
  }
}
