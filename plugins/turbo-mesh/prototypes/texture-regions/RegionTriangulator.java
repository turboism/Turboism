// Offline prototype bridge: use the project's actual CDT, with shared texture boundaries.
import dev.turboism.plugin.turbomesh.domain.ConstraintDelaunay;
import dev.turboism.sdk.cubism.model.Point2;
import java.nio.file.*;
import java.util.*;
public final class RegionTriangulator {
    public static void main(String[] args) throws Exception {
        try (Scanner in = new Scanner(Path.of(args[0]))) {
            in.useLocale(Locale.ROOT);
            int n = in.nextInt(), m = in.nextInt(), r = in.nextInt();
            var points = new ArrayList<Point2>();
            var edges = new ArrayList<int[]>();
            var rings = new ArrayList<int[]>();
            // Normalized coordinates keep the CDT's fixed tolerances in its normal UV scale.
            for (int i=0; i<n; i++) points.add(new Point2((float)(in.nextDouble()/1024.), (float)(in.nextDouble()/1024.)));
            for (int i=0; i<m; i++) edges.add(new int[]{in.nextInt(), in.nextInt()});
            for (int i=0; i<r; i++) {
                int[] ring = new int[in.nextInt()];
                for (int j=0; j<ring.length; j++) ring[j]=in.nextInt();
                rings.add(ring);
            }
            var mesh = ConstraintDelaunay.triangulate(points, edges, null, rings);
            try (var out = new Formatter(args[1], "UTF-8", Locale.ROOT)) {
                out.format("%d %d%n",mesh.points().size(),mesh.triangleIndices().size()/3);
                for (var p:mesh.points()) out.format("%.10f %.10f%n",p.x()*1024.,p.y()*1024.);
                for(int i=0;i<mesh.triangleIndices().size();i+=3)
                    out.format("%d %d %d%n",mesh.triangleIndices().get(i),mesh.triangleIndices().get(i+1),mesh.triangleIndices().get(i+2));
            }
        }
    }
}
