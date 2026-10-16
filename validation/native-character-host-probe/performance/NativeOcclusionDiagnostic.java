package dev.turboism.validation.modelimport3d.host;

import dev.turboism.plugin.modelimport3d.viewer.*;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Test-only reference-depth diagnostic, not a native rendered-RGBA acceptance test. */
public final class NativeOcclusionDiagnostic {
  static final int S = 384;

  record Measurement(int visible, int wrong, int internal, int external, int missing, double maxGap) {}

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--self-test")) {
      selfTest();
      return;
    }
    if (args.length != 2)
      throw new IllegalArgumentException("Expected explicit input GLB and output directory");
    var scene = GlbViewerLoader.load(Files.readAllBytes(Path.of(args[0])), () -> false);
    var request = new SurfaceImport.Request(
        "Occlusion",
        new ParameterId("Yaw"),
        new ParameterId("Pitch"),
        new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0)),
        180,
        180,
        SurfaceImportPlan.MAX_PROJECTED_VERTICES,
        Optional.of(new ParameterId("Roll")));
    // Culling is a candidate paint policy only. The nearest-depth reference
    // must retain the source's double-sided semantics, including any exposed holes.
    System.out.println("candidateForceCulling=" + Boolean.getBoolean("diagnostic.culling"));
    long started = System.nanoTime();
    var plan = NativeCharacterPlan.prepare(scene, request);
    System.out.printf(
        "prepareMs=%.1f partitionSlots=%d cacheSlots=%d%n",
        (System.nanoTime() - started) / 1e6, plan.totalPartitionSlots(), plan.totalCacheSlots());
    System.out.printf(
        "partitions=%d triangles=%d slots=%d\n",
        plan.partitions().size(), plan.totalTriangles(), plan.totalKeyformSlots());
    var out = Path.of(args[1]);
    Files.createDirectories(out);
    for (double yaw : new double[] {0, -30, 30, -90, 90, 180}) {
      var pose = new SurfaceProjection.Pose(yaw, 0);
      var interpolated = new ArrayList<Integer>();
      for (int i = 0; i < plan.partitions().size(); i++) interpolated.add(i);
      double lo = Math.floor(yaw / 90) * 90,
          hi = Math.min(180, lo + 90),
          f = hi == lo ? 0 : (yaw - lo) / (hi - lo);
      int a = NativeCharacterPlan.KEYS.indexOf((float) lo) * 5 + 2,
          b = NativeCharacterPlan.KEYS.indexOf((float) hi) * 5 + 2;
      interpolated.sort(Comparator.<Integer>comparingDouble(
              i -> plan.orders().get(a).get(i) * (1 - f) + plan.orders().get(b).get(i) * f)
          .thenComparingInt(i -> i));
      var m = measure(
          scene, plan, pose, interpolated, out.resolve("yaw" + (int) yaw + "-stored-order.png"));
      var z = new float[plan.partitions().size()][S * S];
      var exact =
          NativeCharacterVisibility.order(plan.partitions(), plan.camera(), scene, pose, S, z);
      var e = measure(scene, plan, pose, exact, out.resolve("yaw" + (int) yaw + "-resorted.png"));
      System.out.printf(Locale.ROOT, "yaw=%.0f stored=%s exactPosePartitionOrder=%s\n", yaw, m, e);
      System.out.flush();
    }
  }

  static Measurement measure(
      ViewerScene scene,
      NativeCharacterPlan.Plan plan,
      SurfaceProjection.Pose pose,
      List<Integer> order,
      Path output)
      throws Exception {
    return measure(scene, plan, pose, order, output, Boolean.getBoolean("diagnostic.culling"));
  }

  static Measurement measure(
      ViewerScene scene,
      NativeCharacterPlan.Plan plan,
      SurfaceProjection.Pose pose,
      List<Integer> order,
      Path output,
      boolean forceCulling) throws Exception {
    float[] front = new float[S * S], paint = new float[S * S];
    Arrays.fill(front, Float.NEGATIVE_INFINITY);
    Arrays.fill(paint, Float.NEGATIVE_INFINITY);
    int[] reference = new int[S * S], owner = new int[S * S];
    Arrays.fill(reference, -1);
    Arrays.fill(owner, -1);
    for (int index : order) {
      var p = plan.partitions().get(index);
      var mesh = scene.meshes().get(p.sourceMeshIndex());
      if (!p.visible() || mesh.defaultOpacity() <= 0) continue;
      var mat = p.materialIndex() < 0 ? null : scene.materials().get(p.materialIndex());
      var tex = mat == null || mat.baseColorTexture() < 0
          ? null
          : scene.textures().get(mat.baseColorTexture());
      var q = SurfaceProjection.project(p.positions(), plan.camera(), pose);
      var xy = q.positions();
      for (int t = 0; t < p.indices().size(); t += 3) {
        int a = p.indices().get(t), b = p.indices().get(t + 1), c = p.indices().get(t + 2);
        double x0 = xy.get(a).x() * S / 1024.0,
            y0 = xy.get(a).y() * S / 1024.0,
            x1 = xy.get(b).x() * S / 1024.0,
            y1 = xy.get(b).y() * S / 1024.0,
            x2 = xy.get(c).x() * S / 1024.0,
            y2 = xy.get(c).y() * S / 1024.0;
        double d = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (Math.abs(d) < 1e-8 || !p.doubleSided() && d >= 0) continue;
        int xa = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2)))),
            xb = Math.min(S - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int ya = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2)))),
            yb = Math.min(S - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        for (int y = ya; y <= yb; y++)
          for (int x = xa; x <= xb; x++) {
            double w0 = ((y1 - y2) * (x + .5 - x2) + (x2 - x1) * (y + .5 - y2)) / d,
                w1 = ((y2 - y0) * (x + .5 - x2) + (x0 - x2) * (y + .5 - y2)) / d,
                w2 = 1 - w0 - w1;
            if (w0 < 0 || w1 < 0 || w2 < 0) continue;
            if (mat != null && !mat.alphaMode().equals("OPAQUE")) {
              double alpha = mat.baseColorFactor()[3] * mesh.defaultOpacity();
              if (tex != null) {
                double
                    u =
                        w0 * p.uvs().get(a).x()
                            + w1 * p.uvs().get(b).x()
                            + w2 * p.uvs().get(c).x(),
                    v =
                        w0 * p.uvs().get(a).y()
                            + w1 * p.uvs().get(b).y()
                            + w2 * p.uvs().get(c).y();
                int tx = Math.min(tex.width() - 1, (int) ((u - Math.floor(u)) * tex.width())),
                    ty = Math.min(tex.height() - 1, (int) ((v - Math.floor(v)) * tex.height()));
                alpha *= (tex.argb()[ty * tex.width() + tx] >>> 24) / 255.0;
              }
              if (mesh.colors() != null) {
                var colors = mesh.colors();
                alpha *= w0 * colors[p.sourceSlotMapping().get(a) * 4 + 3]
                    + w1 * colors[p.sourceSlotMapping().get(b) * 4 + 3]
                    + w2 * colors[p.sourceSlotMapping().get(c) * 4 + 3];
              }
              if (alpha < (mat.alphaMode().equals("MASK") ? mat.alphaCutoff() : .5) || alpha <= 0)
                continue;
            }
            float z = (float) (w0 * q.depths().get(a)
                + w1 * q.depths().get(b)
                + w2 * q.depths().get(c));
            int k = y * S + x;
            if (z > front[k]) {
              front[k] = z;
              reference[k] = index;
            }
            if (!forceCulling || d < 0) {
              paint[k] = z;
              owner[k] = index;
            }
          }
      }
    }
    int visible = 0, wrong = 0, internal = 0, external = 0, missing = 0;
    int[][] errors = new int[plan.partitions().size()][3];
    double[] partitionGaps = new double[plan.partitions().size()];
    double maxGap = 0;
    var image = new BufferedImage(S, S, BufferedImage.TYPE_INT_RGB);
    for (int k = 0; k < S * S; k++) {
      if (reference[k] < 0) continue;
      visible++;
      if (owner[k] < 0) missing++;
      double gap = front[k] - paint[k];
      boolean bad = gap > 1e-5;
      if (bad) {
        wrong++;
        if (reference[k] == owner[k]) internal++;
        else external++;
        maxGap = Math.max(maxGap, gap);
        errors[reference[k]][reference[k] == owner[k] ? 0 : 1]++;
        if (owner[k] < 0) errors[reference[k]][2]++;
        partitionGaps[reference[k]] = Math.max(partitionGaps[reference[k]], gap);
      }
      image.setRGB(k % S, k / S, bad ? (reference[k] == owner[k] ? 0xff3030 : 0xffb020) : 0x657585);
    }
    ImageIO.write(image, "PNG", output.toFile());
    // Attribute source-depth errors before changing partition policy. A missing pixel
    // is also external, so internal + external (not all three columns) equals wrong.
    var report = new StringBuilder("partition,sourceMesh,triangles,internal,external,missing,maxDepthGap\n");
    for (int i = 0; i < errors.length; i++) {
      if (errors[i][0] + errors[i][1] == 0) continue;
      var p = plan.partitions().get(i);
      report.append(i).append(',').append(p.sourceMeshIndex()).append(',')
          .append(p.triangleCount()).append(',').append(errors[i][0]).append(',')
          .append(errors[i][1]).append(',').append(errors[i][2]).append(',')
          .append(Double.toString(partitionGaps[i])).append('\n');
    }
    Files.writeString(output.resolveSibling(output.getFileName() + ".errors.csv"), report);
    return new Measurement(visible, wrong, internal, external, missing, maxGap);
  }

  /** Public synthetic regression: culling a source-visible back face must count as a hole. */
  static void selfTest() throws Exception {
    var mesh = new ViewerScene.Mesh("Double-sided source", new float[] {-1,-1,0, 1,-1,0, 0,1,0},
        new float[9], new float[] {0,0, 1,0, .5f,1}, null, null, new int[] {0,2,1}, 0);
    var material = new ViewerScene.Material("Source", new float[] {1,1,1,1}, -1, -1,
        new float[3], "OPAQUE", .5f, true, true);
    var scene = new ViewerScene(List.of(mesh), List.of(material), List.of(),
        new ViewerScene.Bounds(-1,-1,0,1,1,0), new ViewerScene.Stats(0,1,1,1,0,0,0));
    var request = new SurfaceImport.Request("Reference", new ParameterId("Yaw"), new ParameterId("Pitch"),
        new SurfaceProjection.Camera(512,512,200,new SurfaceProjection.Vertex(0,0,0)),
        180,180,SurfaceImportPlan.MAX_PROJECTED_VERTICES,Optional.empty());
    var plan = NativeCharacterPlan.prepare(scene, request);
    var output = Files.createTempDirectory("native-culling-reference-");
    var source = measure(scene, plan, new SurfaceProjection.Pose(0,0), List.of(0), output.resolve("source.png"), false);
    var culled = measure(scene, plan, new SurfaceProjection.Pose(0,0), List.of(0), output.resolve("culled.png"), true);
    if (source.visible() <= 0 || source.wrong() != 0 || source.missing() != 0
        || culled.visible() != source.visible() || culled.missing() != source.visible()
        || culled.wrong() != source.visible())
      throw new AssertionError("Culling changed or hid reference coverage: source=" + source + ", candidate=" + culled);
    System.out.println("Culling reference regression PASS: source=" + source + ", candidate=" + culled);
    System.out.println("Synthetic evidence: " + output);
  }

}
