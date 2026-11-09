package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.TexturePixels;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Writes one probed mesh to a dump directory the offline renderer reads.
 *
 * <p>The layout matches the earlier Turbo Mesh host dump, so the existing comparison tooling keeps
 * working: {@code meta.properties}, {@code positions.txt}, {@code uvs.txt}, {@code indices.txt} and
 * {@code texture.bin}. Each variant of a probed mesh lives in its own subdirectory, so one mesh
 * directory holds the native baseline next to every custom algorithm result.</p>
 *
 * <p>Positions and uvs are written as {@code x,y} lines with the exact {@code Float.toString} form.
 * A rounded decimal would hide the small host differences the comparison exists to find.</p>
 */
final class AutoMeshDumpWriter {

    /** One line per vertex or triangle index; the renderer reads the file as text. */
    private static final char LINE_SEPARATOR = '\n';

    private static final char VALUE_SEPARATOR = ',';

    /** File names of one dumped variant. */
    static final String META_FILE = "meta.properties";

    static final String POSITIONS_FILE = "positions.txt";
    static final String UVS_FILE = "uvs.txt";
    static final String INDICES_FILE = "indices.txt";
    static final String TEXTURE_FILE = "texture.bin";

    private static final String KEY_ID = "id";
    private static final String KEY_VARIANT = "variant";
    private static final String KEY_VERTICES = "vertices";
    private static final String KEY_TRIANGLES = "triangles";
    private static final String KEY_DIGEST = "digest";
    private static final String KEY_U0 = "u0";
    private static final String KEY_V0 = "v0";
    private static final String KEY_U1 = "u1";
    private static final String KEY_V1 = "v1";
    private static final String KEY_TEXTURE_WIDTH = "texture.width";
    private static final String KEY_TEXTURE_HEIGHT = "texture.height";
    private static final String KEY_UV_TO_PIXEL_PREFIX = "texture.uv2px.";
    private static final String KEY_DIAGNOSTIC = "diagnostic";

    private AutoMeshDumpWriter() {}

    /**
     * Writes one variant of one probed mesh.
     *
     * @param directory the variant directory; it is created when absent
     * @param meshId the ArtMesh identity the variant belongs to
     * @param variant the variant label, for example {@code native} or {@code turbo-mesh.region}
     * @param geometry the geometry to write
     * @param texture the texture raster of the mesh, or {@code null} when the host exposed none
     * @param diagnostic free-form note stored in the metadata, or an empty string
     * @throws IOException when a file cannot be written
     */
    static void write(
            final Path directory,
            final String meshId,
            final String variant,
            final ArtMeshGeometry geometry,
            final TexturePixels texture,
            final String diagnostic)
            throws IOException {
        Files.createDirectories(directory);
        writePoints(directory.resolve(POSITIONS_FILE), geometry.positions());
        writePoints(directory.resolve(UVS_FILE), geometry.uvs());
        writeIndices(directory.resolve(INDICES_FILE), geometry.triangleIndices());
        if (texture != null) {
            writeTexture(directory.resolve(TEXTURE_FILE), texture);
        }
        Files.writeString(
                directory.resolve(META_FILE),
                metadata(meshId, variant, geometry, texture, diagnostic),
                StandardCharsets.UTF_8);
    }

    /**
     * Renders the metadata block of one dumped variant.
     *
     * <p>The extent lines let the renderer frame the mesh without rescanning the coordinates, and the
     * digest lets an offline comparison match a dump against a result file.</p>
     *
     * @param meshId the ArtMesh identity
     * @param variant the variant label
     * @param geometry the dumped geometry
     * @param texture the dumped texture, or {@code null}
     * @param diagnostic free-form note, or an empty string
     * @return the properties file content, newline-terminated
     */
    static String metadata(
            final String meshId,
            final String variant,
            final ArtMeshGeometry geometry,
            final TexturePixels texture,
            final String diagnostic) {
        final Properties properties = new Properties();
        properties.setProperty(KEY_ID, meshId);
        properties.setProperty(KEY_VARIANT, variant);
        properties.setProperty(
                KEY_VERTICES, Integer.toString(geometry.positions().size()));
        properties.setProperty(
                KEY_TRIANGLES, Integer.toString(geometry.triangleIndices().size() / 3));
        properties.setProperty(KEY_DIGEST, AutoMeshGeometryDigest.of(geometry));
        final double[] extent = uvExtent(geometry);
        properties.setProperty(KEY_U0, Double.toString(extent[0]));
        properties.setProperty(KEY_V0, Double.toString(extent[1]));
        properties.setProperty(KEY_U1, Double.toString(extent[2]));
        properties.setProperty(KEY_V1, Double.toString(extent[3]));
        if (texture != null) {
            properties.setProperty(KEY_TEXTURE_WIDTH, Integer.toString(texture.width()));
            properties.setProperty(KEY_TEXTURE_HEIGHT, Integer.toString(texture.height()));
            final double[] affine = texture.uvToPixel();
            for (int index = 0; index < affine.length; index++) {
                properties.setProperty(KEY_UV_TO_PIXEL_PREFIX + index, Double.toString(affine[index]));
            }
        }
        if (diagnostic != null && !diagnostic.isBlank()) {
            properties.setProperty(KEY_DIAGNOSTIC, diagnostic);
        }
        final StringBuilder text = new StringBuilder();
        for (final String name : new java.util.TreeSet<>(properties.stringPropertyNames())) {
            text.append(name).append('=').append(properties.getProperty(name)).append(LINE_SEPARATOR);
        }
        return text.toString();
    }

    /**
     * Returns the uv bounding box as {@code [u0, v0, u1, v1]}.
     *
     * <p>An empty mesh reports an all-zero box instead of the inverted sentinels an empty scan
     * produces, so the renderer never frames a mesh outside the texture.</p>
     *
     * @param geometry the geometry to measure
     * @return four doubles in {@code u0, v0, u1, v1} order
     */
    static double[] uvExtent(final ArtMeshGeometry geometry) {
        if (geometry.uvs().isEmpty()) {
            return new double[] {0.0, 0.0, 0.0, 0.0};
        }
        double u0 = Double.MAX_VALUE;
        double v0 = Double.MAX_VALUE;
        double u1 = -Double.MAX_VALUE;
        double v1 = -Double.MAX_VALUE;
        for (final Point2 uv : geometry.uvs()) {
            u0 = Math.min(u0, uv.x());
            v0 = Math.min(v0, uv.y());
            u1 = Math.max(u1, uv.x());
            v1 = Math.max(v1, uv.y());
        }
        return new double[] {u0, v0, u1, v1};
    }

    private static void writePoints(final Path file, final List<Point2> points) throws IOException {
        final StringBuilder text = new StringBuilder();
        for (final Point2 point : points) {
            text.append(String.format(Locale.ROOT, "%s%c%s%c", point.x(), VALUE_SEPARATOR, point.y(), LINE_SEPARATOR));
        }
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    }

    private static void writeIndices(final Path file, final List<Integer> indices) throws IOException {
        final StringBuilder text = new StringBuilder();
        for (final int index : indices) {
            text.append(index).append(LINE_SEPARATOR);
        }
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    }

    private static void writeTexture(final Path file, final TexturePixels texture) throws IOException {
        try (OutputStream stream = Files.newOutputStream(file);
                DataOutputStream output = new DataOutputStream(stream)) {
            output.writeInt(texture.width());
            output.writeInt(texture.height());
            for (final int pixel : texture.argb()) {
                output.writeInt(pixel);
            }
        }
    }
}
