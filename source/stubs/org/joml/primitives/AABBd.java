package org.joml.primitives;
public class AABBd implements AABBdc {
  public double minX, minY, minZ, maxX, maxY, maxZ;
  public AABBd setMin(double x, double y, double z) { return this; }
  public AABBd setMax(double x, double y, double z) { return this; }
  public double minX() { return minX; } public double minY() { return minY; } public double minZ() { return minZ; }
  public double maxX() { return maxX; } public double maxY() { return maxY; } public double maxZ() { return maxZ; }
}
