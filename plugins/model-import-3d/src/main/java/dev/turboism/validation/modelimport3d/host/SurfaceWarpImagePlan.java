package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.cubism.model.Point2;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;

/** Pure shared-image preparation. Source UVs remain in the geometry plan;
 * image-pixel/shader coordinates and default Warp-local positions are separate domains.
 * Uses the existing source material conversion, not a native mesh color edit. */
final class SurfaceWarpImagePlan {
    private SurfaceWarpImagePlan() {}

    private static final int TILE_WIDTH = 512, TILE_HEIGHT = 256;

    private record MaterialKey(EmbeddedBaseColorTexture texture, List<Double> factor) {
        MaterialKey {
            factor = List.copyOf(factor);
        }
    }

    record MeshInput(ArtMeshGeometry imageGeometry, List<Point2> defaultLocal) {
        MeshInput {
            Objects.requireNonNull(imageGeometry);
            defaultLocal = List.copyOf(defaultLocal);
        }
    }

    static final class Plan {
        final int width, height;
        final List<Integer> sourceMaterialTiles;
        final List<MeshInput> inputs;
        final boolean derived;
        final List<Integer> meshImages;
        private final List<byte[]> pngs;

        private Plan(int width, int height, List<Integer> tiles, List<MeshInput> inputs, byte[] png) {
            this(width, height, tiles, inputs, List.of(png), java.util.Collections.nCopies(inputs.size(), 0), false);
        }

        private Plan(
                int width,
                int height,
                List<Integer> tiles,
                List<MeshInput> inputs,
                List<byte[]> pngs,
                List<Integer> meshImages,
                boolean derived) {
            this.width = width;
            this.height = height;
            sourceMaterialTiles = List.copyOf(tiles);
            this.inputs = List.copyOf(inputs);
            this.pngs = pngs.stream().map(byte[]::clone).toList();
            this.meshImages = List.copyOf(meshImages);
            this.derived = derived;
            if (inputs.size() != meshImages.size()
                    || pngs.isEmpty()
                    || meshImages.stream().anyMatch(i -> i < 0 || i >= pngs.size()))
                throw new IllegalArgumentException("complete per-mesh image mapping required");
        }

        int imageCount() {
            return pngs.size();
        }

        byte[] png(int image) {
            return pngs.get(image).clone();
        }

        byte[] png() {
            if (pngs.size() != 1) throw new IllegalStateException("per-chart image index required");
            return png(0);
        }

        AuthorizedImageContent newGrant(int image) {
            return AuthorizedImageContent.ofDimensions(
                    pngs.get(image), "GLB original base-color material mapping", width, height);
        }

        AuthorizedImageContent newGrant() {
            if (pngs.size() != 1) throw new IllegalStateException("per-chart image index required");
            return newGrant(0);
        }
    }

    /** Changes only image colors, retaining image-space geometry and sampling. */
    static Plan faceColors(Plan source) throws IOException {
        var pngs = new ArrayList<byte[]>();
        for (int color : SurfaceBaseColorPlan.FACE_PALETTE) {
            var image = new BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[source.width];
            Arrays.fill(row, color);
            for (int y = 0; y < source.height; y++) image.setRGB(0, y, source.width, 1, row, 0, source.width);
            var out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) throw new IOException("PNG encoder unavailable");
            pngs.add(out.toByteArray());
        }
        var images = new ArrayList<Integer>();
        for (int i = 0; i < source.inputs.size(); i++) images.add(i % pngs.size());
        return new Plan(
                source.width, source.height, source.sourceMaterialTiles, source.inputs, pngs, images, source.derived);
    }

    private record SourceSlot(int surface, int sourceSlot) {}

    private record SourceMesh(List<SourceSlot> slots, List<Integer> indices, List<Point2> defaults) {}

    static Plan compile(SurfaceWarpChartPlan.Plan geometry) throws IOException {
        Objects.requireNonNull(geometry);
        var inputs = new ArrayList<SourceMesh>();
        for (var mesh : geometry.meshes()) {
            var slots = new ArrayList<SourceSlot>();
            for (int i = 0; i < mesh.slotIdentities().size(); i++) {
                var slot = mesh.slotIdentities().get(i);
                var source = geometry.sources().get(slot.surface());
                if (!mesh.sourcePositions().get(i).equals(source.positions().get(slot.sourceSlot()))
                        || !mesh.sourceUvs().get(i).equals(source.textureUvs().get(slot.sourceSlot())))
                    throw new IllegalArgumentException("source image geometry/UV provenance differs");
                slots.add(new SourceSlot(slot.surface(), slot.sourceSlot()));
            }
            inputs.add(new SourceMesh(slots, mesh.indices(), mesh.warpLocal()));
        }
        return compileSources(geometry.sources(), inputs);
    }
    /** Editable single-chart planes and original-corner texture baking. This
     * preparation is pure; unsupported chart/material coverage fails before mutation. */
    static Plan compileDerived(SurfaceWarpChartPlan.Plan geometry) throws IOException {
        var sourcePlan = compile(geometry);
        var source = ImageIO.read(new ByteArrayInputStream(sourcePlan.png()));
        if (source == null) throw new IOException("original material decode unavailable");
        try {
            var inputs = new ArrayList<MeshInput>();
            var images = new ArrayList<byte[]>();
            var mapping = new ArrayList<Integer>();
            var hashes = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < geometry.meshes().size(); i++) {
                var plane = SurfaceDerivedPlanePlan.compile(geometry.meshes().get(i));
                var texture = SurfaceDerivedTexturePlan.bake(
                        plane, sourcePlan.inputs.get(i).imageGeometry(), source);
                byte[] png = texture.png();
                String hash;
                try {
                    hash = HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(png));
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
                Integer image = hashes.get(hash);
                if (image == null) {
                    image = images.size();
                    hashes.put(hash, image);
                    images.add(png);
                } else if (!Arrays.equals(images.get(image), png))
                    throw new IllegalStateException("material image hash collision");
                if ((long) images.size() * SurfaceDerivedPlanePlan.IMAGE_SIZE * SurfaceDerivedPlanePlan.IMAGE_SIZE
                        > 33_554_432L)
                    throw new IllegalArgumentException("derived images exceed two-page export pixel budget");
                mapping.add(image);
                inputs.add(new MeshInput(plane.geometry(), plane.defaultLocal()));
            }
            return new Plan(
                    SurfaceDerivedPlanePlan.IMAGE_SIZE,
                    SurfaceDerivedPlanePlan.IMAGE_SIZE,
                    sourcePlan.sourceMaterialTiles,
                    inputs,
                    images,
                    mapping,
                    true);
        } finally {
            source.flush();
        }
    }
    /** Reflect both the editable source plane and its bitmap, keeping the rendered
     * material mapping and original 3D forms unchanged. This does not repair mixed
     * winding or zero-area UV faces: callers must partition those explicitly first. */
    static Plan reflectVertically(Plan original) throws IOException {
        Objects.requireNonNull(original);
        var pngs = new ArrayList<byte[]>();
        for (int i = 0; i < original.imageCount(); i++) {
            var image = ImageIO.read(new ByteArrayInputStream(original.png(i)));
            if (image == null || image.getWidth() != original.width || image.getHeight() != original.height)
                throw new IOException("bounded source reflection dimensions");
            var reflected = new BufferedImage(original.width, original.height, BufferedImage.TYPE_INT_ARGB);
            try {
                for (int y = 0; y < original.height; y++)
                    for (int x = 0; x < original.width; x++)
                        reflected.setRGB(x, original.height - 1 - y, image.getRGB(x, y));
                var bytes = new ByteArrayOutputStream();
                if (!ImageIO.write(reflected, "PNG", bytes))
                    throw new IOException("reflected image encoder unavailable");
                pngs.add(bytes.toByteArray());
            } finally {
                image.flush();
                reflected.flush();
            }
        }
        var inputs = new ArrayList<MeshInput>();
        for (var input : original.inputs) {
            var source = input.imageGeometry();
            var positions = source.positions().stream()
                    .map(p -> new Point2(p.x(), original.height - p.y()))
                    .toList();
            var uvs = source.uvs().stream()
                    .map(p -> new Point2(p.x(), 1f - p.y()))
                    .toList();
            inputs.add(
                    new MeshInput(new ArtMeshGeometry(positions, uvs, source.triangleIndices()), input.defaultLocal()));
        }
        return new Plan(
                original.width,
                original.height,
                original.sourceMaterialTiles,
                inputs,
                pngs,
                original.meshImages,
                original.derived);
    }

    record EditableVertex(
            SurfaceVertexPlan.Plan geometry, Plan images, SurfaceNativeSourceWindingPlan.Plan provenance) {}

    record EditableTopologyVertex(SurfaceVertexPlan.Plan geometry, Plan images, int nativeQueries) {}

    /** Refine only source planes rejected by the detached native triangulator.
     * Keep every source face/corner and reuse the same material images. No model
     * objects are created here; callers must recompute authoring cost afterwards. */
    static EditableTopologyVertex refineEditableVertex(
            SurfaceVertexPlan.Plan geometry,
            Plan images,
            SurfaceEditableTopologyPlan.Limits limits,
            java.util.function.Function<ArtMeshGeometry, dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation>
                    preview) {
        Objects.requireNonNull(geometry);
        Objects.requireNonNull(images);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(preview);
        if (!images.derived || geometry.meshes().size() != images.inputs.size())
            throw new IllegalArgumentException("complete editable source planes required");
        var meshes = new ArrayList<SurfaceVertexPlan.Mesh>();
        var inputs = new ArrayList<MeshInput>();
        var mapping = new ArrayList<Integer>();
        int queries = 0, slots = 0;
        for (int i = 0; i < geometry.meshes().size(); i++) {
            var mesh = geometry.meshes().get(i);
            var input = images.inputs.get(i);
            if (!mesh.indices().equals(input.imageGeometry().triangleIndices())
                    || mesh.positions().size()
                            != input.imageGeometry().positions().size()
                    || mesh.positions().size() != input.defaultLocal().size())
                throw new IllegalArgumentException("editable source corner/face mapping differs");
            if (meshes.size() >= limits.maximumParts()
                    || queries >= limits.maximumQueries()
                    || limits.maximumTotalSlots() - slots < 3)
                throw new IllegalArgumentException("editable topology global budget exceeded");
            var plan = SurfaceEditableTopologyPlan.compile(
                    input.imageGeometry(),
                    new SurfaceEditableTopologyPlan.Limits(
                            limits.maximumParts() - meshes.size(),
                            limits.maximumQueries() - queries,
                            limits.maximumTotalSlots() - slots),
                    preview);
            queries += plan.nativeQueries();
            slots += plan.totalSlots();
            for (var part : plan.parts()) {
                var selected = part.sourceSlots();
                meshes.add(new SurfaceVertexPlan.Mesh(
                        selected.stream().map(mesh.positions()::get).toList(),
                        selected.stream().map(mesh.uvs()::get).toList(),
                        selected.stream().map(mesh.slots()::get).toList(),
                        part.geometry().triangleIndices(),
                        mesh.triangles().subList(part.firstFace(), part.endFace())));
                inputs.add(new MeshInput(
                        part.geometry(),
                        selected.stream().map(input.defaultLocal()::get).toList()));
                mapping.add(images.meshImages.get(i));
            }
        }
        return new EditableTopologyVertex(
                new SurfaceVertexPlan.Plan(geometry.sources(), meshes, slots),
                new Plan(images.width, images.height, images.sourceMaterialTiles, inputs, images.pngs, mapping, true),
                queries);
    }
    /** Native source planes use a positive image-space winding. Original 3D faces
     * and UV corner attributes remain in geometry/provenance, including zero UV
     * area faces whose material mapping needs its own nondegenerate image plane. */
    static EditableVertex compileEditableVertex(
            SurfaceVertexPlan.Plan original, SurfaceProjection.Camera camera, int maximumMeshes) throws IOException {
        var provenance = SurfaceNativeSourceWindingPlan.compile(original, maximumMeshes);
        var geometry = new SurfaceVertexPlan.Plan(
                provenance.sources(),
                provenance.groups().stream()
                        .map(SurfaceNativeSourceWindingPlan.Group::mesh)
                        .toList(),
                provenance.repeatedSlots());
        var shared = compileVertex(geometry, camera);
        var reflected = reflectVertically(shared);
        long derived = provenance.groups().stream()
                .filter(g -> g.plane() == SurfaceNativeSourceWindingPlan.Plane.NEEDS_DERIVED_PLANE)
                .count();
        if ((2L + derived) * shared.width * shared.height > 33_554_432L)
            throw new IllegalArgumentException("editable source images exceed decoded pixel budget");
        var pngs = new ArrayList<byte[]>();
        pngs.add(shared.png());
        pngs.add(reflected.png());
        var mapping = new ArrayList<Integer>();
        var inputs = new ArrayList<MeshInput>();
        var source = ImageIO.read(new ByteArrayInputStream(shared.png()));
        if (source == null) throw new IOException("source image unavailable");
        try {
            for (int i = 0; i < provenance.groups().size(); i++) {
                var group = provenance.groups().get(i);
                var input = shared.inputs.get(i);
                if (group.plane() == SurfaceNativeSourceWindingPlan.Plane.ORIGINAL_IMAGE) {
                    inputs.add(input);
                    mapping.add(0);
                } else if (group.plane() == SurfaceNativeSourceWindingPlan.Plane.REFLECTED_IMAGE) {
                    inputs.add(reflected.inputs.get(i));
                    mapping.add(1);
                } else {
                    if (group.mesh().indices().size() != 3
                            || input.imageGeometry().positions().size() != 3)
                        throw new IllegalArgumentException(
                                "one original triangle with three retained corners required");
                    int border = 4;
                    float right = shared.width - border, bottom = shared.height - border;
                    var positions =
                            List.of(new Point2(border, border), new Point2(right, border), new Point2(border, bottom));
                    var uvs = positions.stream()
                            .map(v -> new Point2(v.x() / shared.width, v.y() / shared.height))
                            .toList();
                    inputs.add(
                            new MeshInput(new ArtMeshGeometry(positions, uvs, List.of(0, 1, 2)), input.defaultLocal()));
                    mapping.add(pngs.size());
                    pngs.add(bakeTriangleSource(
                            source, input.imageGeometry().positions(), shared.width, shared.height, border));
                }
                var nativePlane = inputs.get(i).imageGeometry();
                if (new java.util.HashSet<>(nativePlane.positions()).size()
                        != nativePlane.positions().size())
                    throw new IllegalArgumentException(
                            "editable source plane still aliases original corners; finer source partition required");
                for (int corner = 0; corner < nativePlane.triangleIndices().size(); corner += 3) {
                    var pa = nativePlane
                            .positions()
                            .get(nativePlane.triangleIndices().get(corner));
                    var pb = nativePlane
                            .positions()
                            .get(nativePlane.triangleIndices().get(corner + 1));
                    var pc = nativePlane
                            .positions()
                            .get(nativePlane.triangleIndices().get(corner + 2));
                    double area = ((double) pb.x() - pa.x()) * ((double) pc.y() - pa.y())
                            - ((double) pb.y() - pa.y()) * ((double) pc.x() - pa.x());
                    if (!(area > 0))
                        throw new IllegalArgumentException(
                                "editable source plane must preserve positive nonzero native face winding");
                }
            }
        } finally {
            source.flush();
        }
        return new EditableVertex(
                geometry,
                new Plan(shared.width, shared.height, shared.sourceMaterialTiles, inputs, pngs, mapping, true),
                provenance);
    }

    private static byte[] bakeTriangleSource(
            BufferedImage source, List<Point2> corners, int width, int height, int border) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        try {
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) {
                    double v = Math.max(0, Math.min(1, (x + .5 - border) / (width - 2d * border)));
                    double w = Math.max(0, Math.min(1, (y + .5 - border) / (height - 2d * border)));
                    // Unused texels and the gutter sample the nearest triangle edge.
                    // The triangle interior keeps the original affine UV mapping.
                    if (v + w > 1) {
                        double sum = v + w;
                        v /= sum;
                        w /= sum;
                    }
                    double u = 1 - v - w;
                    double sx = u * corners.get(0).x()
                            + v * corners.get(1).x()
                            + w * corners.get(2).x();
                    double sy = u * corners.get(0).y()
                            + v * corners.get(1).y()
                            + w * corners.get(2).y();
                    int argb = 0;
                    for (int channel = 0; channel < 4; channel++)
                        argb |= ((int) Math.rint(sampleChannel(source, sx, sy, 24 - channel * 8)))
                                << (24 - channel * 8);
                    image.setRGB(x, y, argb);
                }
            var bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "PNG", bytes)) throw new IOException("derived triangle PNG encoder unavailable");
            return bytes.toByteArray();
        } finally {
            image.flush();
        }
    }

    private static double sampleChannel(BufferedImage image, double x, double y, int shift) {
        x -= .5;
        y -= .5;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double dx = x - ix, dy = y - iy;
        double a = (image.getRGB(
                                Math.max(0, Math.min(image.getWidth() - 1, ix)),
                                Math.max(0, Math.min(image.getHeight() - 1, iy)))
                        >>> shift)
                & 255;
        double b = (image.getRGB(
                                Math.max(0, Math.min(image.getWidth() - 1, ix + 1)),
                                Math.max(0, Math.min(image.getHeight() - 1, iy)))
                        >>> shift)
                & 255;
        double c = (image.getRGB(
                                Math.max(0, Math.min(image.getWidth() - 1, ix)),
                                Math.max(0, Math.min(image.getHeight() - 1, iy + 1)))
                        >>> shift)
                & 255;
        double d = (image.getRGB(
                                Math.max(0, Math.min(image.getWidth() - 1, ix + 1)),
                                Math.max(0, Math.min(image.getHeight() - 1, iy + 1)))
                        >>> shift)
                & 255;
        return (a * (1 - dx) + b * dx) * (1 - dy) + (c * (1 - dx) + d * dx) * dy;
    }

    static Plan compileVertex(SurfaceVertexPlan.Plan geometry, SurfaceProjection.Camera camera) throws IOException {
        Objects.requireNonNull(geometry);
        var inputs = new ArrayList<SourceMesh>();
        for (var mesh : geometry.meshes()) {
            var slots = new ArrayList<SourceSlot>();
            for (int i = 0; i < mesh.slots().size(); i++) {
                var slot = mesh.slots().get(i);
                var source = geometry.sources().get(slot.surface());
                if (!mesh.positions().get(i).equals(source.positions().get(slot.sourceSlot()))
                        || !mesh.uvs().get(i).equals(source.textureUvs().get(slot.sourceSlot())))
                    throw new IllegalArgumentException("vertex source image provenance differs");
                slots.add(new SourceSlot(slot.surface(), slot.sourceSlot()));
            }
            inputs.add(new SourceMesh(
                    slots,
                    mesh.indices(),
                    SurfaceProjection.project(mesh.positions(), camera, new SurfaceProjection.Pose(0, 0))
                            .positions()));
        }
        return compileSources(geometry.sources(), inputs);
    }

    private static Plan compileSources(List<GlbSurfaceReader.Surface> sources, List<SourceMesh> meshes)
            throws IOException {
        var materialIndices = new LinkedHashMap<MaterialKey, Integer>();
        var mapping = new ArrayList<Integer>();
        for (var source : sources) {
            SurfaceBaseColorPlan.argb(source.baseColor());
            var key = new MaterialKey(source.texture(), source.baseColor());
            Integer index = materialIndices.get(key);
            if (index == null) {
                index = materialIndices.size();
                materialIndices.put(key, index);
            }
            mapping.add(index);
        }
        int count = materialIndices.size();
        if (count < 1 || count > 8)
            throw new IllegalArgumentException("shared source materials exceed existing image RGBA budget");
        for (var mesh : meshes)
            for (var slot : mesh.slots()) {
                var uv = sources.get(slot.surface()).textureUvs().get(slot.sourceSlot());
                if (uv.x() < 0 || uv.x() > 1 || uv.y() < 0 || uv.y() > 1)
                    throw new IllegalArgumentException("source image UV outside unit domain");
            }
        var materials = new ArrayList<SurfaceBaseColorPlan.Material>();
        int tileWidth = TILE_WIDTH, tileHeight = TILE_HEIGHT;
        for (var key : materialIndices.keySet()) {
            var material = SurfaceBaseColorPlan.sourceMaterial(key.texture(), key.factor());
            materials.add(material);
            tileWidth = Math.max(tileWidth, material.width());
            tileHeight = Math.max(tileHeight, material.height());
        }
        int width = tileWidth;
        while (width < count * tileWidth) width *= 2;
        if ((long) width * tileHeight > AuthorizedImageContent.MAX_RGBA_BYTES / 4L)
            throw new IllegalArgumentException("power-of-two shared source image exceeds RGBA budget");
        var image = new BufferedImage(width, tileHeight, BufferedImage.TYPE_INT_ARGB);
        for (var entry : materialIndices.entrySet()) {
            var material = materials.get(entry.getValue());
            var tile = ImageIO.read(new ByteArrayInputStream(material.png()));
            if (tile == null || tile.getWidth() != material.width() || tile.getHeight() != material.height())
                throw new IOException("source material tile dimensions");
            for (int y = 0; y < tileHeight; y++)
                for (int x = 0; x < tileWidth; x++)
                    image.setRGB(
                            entry.getValue() * tileWidth + x,
                            y,
                            tile.getRGB(Math.min(x, tile.getWidth() - 1), Math.min(y, tile.getHeight() - 1)));
        }
        var inputs = new ArrayList<MeshInput>();
        for (var mesh : meshes) {
            var positions = new ArrayList<Point2>();
            var shader = new ArrayList<Point2>();
            for (int i = 0; i < mesh.slots().size(); i++) {
                var slot = mesh.slots().get(i);
                var source = sources.get(slot.surface());
                var uv = source.textureUvs().get(slot.sourceSlot());
                int tile = mapping.get(slot.surface());
                var material = materials.get(tile);
                int sourceWidth =
                        source.texture() == null ? 256 : source.texture().width();
                int sourceHeight =
                        source.texture() == null ? 128 : source.texture().height();
                int border =
                        source.texture() == null || material.width() != sourceWidth || material.height() != sourceHeight
                                ? 1
                                : 0;
                float x = (float) (tile * tileWidth + border + (double) sourceWidth * uv.x()),
                        y = (float) (border + (double) sourceHeight * uv.y());
                positions.add(new Point2(x, y));
                shader.add(new Point2(x / width, y / tileHeight));
            }
            inputs.add(new MeshInput(new ArtMeshGeometry(positions, shader, mesh.indices()), mesh.defaults()));
        }
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("source atlas encoder unavailable");
        return new Plan(width, tileHeight, mapping, inputs, bytes.toByteArray());
    }
}
