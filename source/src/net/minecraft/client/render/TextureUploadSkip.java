package net.minecraft.client.render;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.option.GameSettings;
import net.minecraft.client.render.dynamictexture.DynamicTexture;
import net.minecraft.client.render.texture.stitcher.IconCoordinate;
import net.minecraft.client.util.helper.Buffer;
import org.lwjgl.opengl.GL41;

/**
 * Replacement for TextureManager.updateDynamicTexture: same upload, but skipped when the animated
 * texture's pixels did not change since the last upload. Compass/clock/calendar run every tick even
 * with animations OFF, and on Mali every glTexSubImage2D into the atlas costs several ms.
 * -Dbta.texSkip=false restores the original behaviour.
 */
public final class TextureUploadSkip {
  private static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("bta.texSkip"));
  private static final Map<DynamicTexture, byte[][]> LAST_UPLOADED = new IdentityHashMap<>();
  private static final Map<DynamicTexture, long[]> ICON_KEY = new IdentityHashMap<>(); // icon pos + mip setting at last upload
  private static int calls;

  public static void update(TextureManager textureManager, DynamicTexture animation) {
    if (!ON) { textureManager.updateDynamicTexture(animation); return; }
    if (++calls >= 4000) { calls = 0; clear(); } // safety net: re-upload everything now and then
    animation.update();
    IconCoordinate icon = animation.targetTexture;
    int layers = icon.parentAtlas.layerCount;
    long key = ((long) icon.iconX << 32) ^ (icon.iconY & 0xFFFFFFFFL) ^ ((long) (Integer) GameSettings.MIPMAP_LEVELS.value << 56);
    long[] storedKey = ICON_KEY.get(animation);
    if (storedKey == null || storedKey[0] != key) { ICON_KEY.put(animation, new long[]{key}); LAST_UPLOADED.remove(animation); } // icon moved: upload again
    byte[][] lastLayers = LAST_UPLOADED.get(animation);
    if (lastLayers == null || lastLayers.length != layers) { lastLayers = new byte[layers][]; LAST_UPLOADED.put(animation, lastLayers); }
    for (int layer = 0; layer < layers; ++layer) {
      byte[] imageData = animation.getImageData(layer);
      if (lastLayers[layer] != null && Arrays.equals(lastLayers[layer], imageData)) continue; // unchanged: no upload
      if (lastLayers[layer] == null || lastLayers[layer].length != imageData.length) lastLayers[layer] = imageData.clone();
      else System.arraycopy(imageData, 0, lastLayers[layer], 0, imageData.length);
      upload(animation, icon, layer, imageData);
    }
  }

  /** Same as the body of TextureManager.updateDynamicTexture for one layer. */
  private static void upload(DynamicTexture animation, IconCoordinate icon, int layer, byte[] imageData) {
    Buffer.checkBufferSize(imageData.length);
    Buffer.reset();
    Buffer.buffer.put(imageData);
    Buffer.buffer.position(0).limit(imageData.length);
    icon.parentAtlas.bind(icon.parentAtlas.idToLayerMap.get(layer));
    int atlasX = icon.iconX, atlasY = icon.iconY;
    GL41.glTexSubImage2D(3553, 0, atlasX, atlasY, icon.width, icon.height, 6408, 5121, (ByteBuffer) Buffer.buffer);
    if ((Integer) GameSettings.MIPMAP_LEVELS.value <= 0 || !icon.parentAtlas.hasMipmaps()) return;
    int levels = GL41.glGetTexParameteri(3553, 33085);
    for (int level = 1; level <= levels; ++level) {
      int lod = 1 << level;
      Buffer.reset();
      for (int x = 0; x < icon.width / lod; ++x) {
        for (int y = 0; y < icon.height / lod; ++y) {
          int i = y * lod * icon.width + x * lod;
          int j = y * (icon.width / lod) + x;
          Buffer.buffer.put(j * 4, imageData[i * 4]);
          Buffer.buffer.put(j * 4 + 1, imageData[i * 4 + 1]);
          Buffer.buffer.put(j * 4 + 2, imageData[i * 4 + 2]);
          Buffer.buffer.put(j * 4 + 3, imageData[i * 4 + 3]);
        }
      }
      Buffer.buffer.position(0).limit(icon.width / lod * icon.height / lod * 4);
      GL41.glTexSubImage2D(3553, level, atlasX / lod, atlasY / lod, icon.width / lod, icon.height / lod, 6408, 5121, (ByteBuffer) Buffer.buffer);
    }
  }

  /** Called when textures are reloaded so stale entries do not leak. */
  public static void clear() { LAST_UPLOADED.clear(); ICON_KEY.clear(); }
}
