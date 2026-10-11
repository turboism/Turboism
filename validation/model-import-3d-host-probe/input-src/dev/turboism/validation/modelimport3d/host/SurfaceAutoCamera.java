package dev.turboism.validation.modelimport3d.host;

import java.util.List;

/** One immutable orthographic camera fitted to the transformed source bounds.
 * It preserves source coordinates/units/UVs and contains the whole source bounding
 * sphere for every rotation around its fixed pivot, not just sampled poses.
 */
final class SurfaceAutoCamera {
    private SurfaceAutoCamera() { }
    static SurfaceProjection.Camera fit(List<GlbSurfaceReader.Surface> surfaces,double centerX,double centerY,double radiusPixels) {
        if(surfaces.isEmpty() || !Double.isFinite(radiusPixels) || radiusPixels<=0)
            throw new IllegalArgumentException("nonempty source and positive framing radius required");
        double xmin=Double.POSITIVE_INFINITY,ymin=xmin,zmin=xmin;
        double xmax=Double.NEGATIVE_INFINITY,ymax=xmax,zmax=xmax;
        long count=0;
        for(var surface:surfaces)for(var point:surface.positions()) {
            xmin=Math.min(xmin,point.x());ymin=Math.min(ymin,point.y());zmin=Math.min(zmin,point.z());
            xmax=Math.max(xmax,point.x());ymax=Math.max(ymax,point.y());zmax=Math.max(zmax,point.z());count++;
        }
        if(count==0)throw new IllegalArgumentException("source has no framing vertices");
        var pivot=new SurfaceProjection.Vertex(xmin/2+xmax/2,ymin/2+ymax/2,zmin/2+zmax/2);
        double radius=0;
        for(var surface:surfaces)for(var point:surface.positions())
            radius=Math.max(radius,Math.hypot(Math.hypot(point.x()-pivot.x(),point.y()-pivot.y()),point.z()-pivot.z()));
        if(!Double.isFinite(radius) || radius<=0)throw new IllegalArgumentException("source framing radius is degenerate or overflowing");
        double scale=radiusPixels/radius;
        // Projection rotates about pivot, then restores world coordinates. Align that
        // fixed pivot to the requested canvas center without changing source geometry.
        return new SurfaceProjection.Camera(centerX-scale*pivot.x(),centerY+scale*pivot.y(),scale,pivot);
    }
}
