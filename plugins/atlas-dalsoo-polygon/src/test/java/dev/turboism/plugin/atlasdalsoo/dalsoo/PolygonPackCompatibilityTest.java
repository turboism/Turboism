package dev.turboism.plugin.atlasdalsoo.dalsoo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PolygonPackCompatibilityTest {
    // Deterministic snapshots include the precision-clearance corner retry. Dense L-shapes
    // cover overflow and the corner fallback; FREE exercises the continuous kernel.
    @Test
    void packingRemainsDeterministicIncludingClearanceRetry() throws Exception {
        final Map<String, String> expected = Map.of(
                "lshape-NONE", "e50312d385dcd1b87ccd4a67b8d12a0f08b4bc8a7a594b0086f6a34893d6b976",
                "lshape-QUARTER", "8a13864480d6786cea1c412c48610b56c815fcaa85def8ecea25d9723ab4e37e",
                "lshape-FREE", "c5fa6c966b1321499d2bdb2b0a648d05c460a76468153b1540a60b63ae45ead8",
                "star-NONE", "64673c57238d5e990d114f671dc134e7365264b775ad682f715e667f778ebe37",
                "star-QUARTER", "103639c3139093ca9e8744293c1c05ccbdd6189a9d0e6253421099303aa7f5aa",
                "star-FREE", "985ea03187907e13bda1992453df1ea033b2d8ab971ceb03a57919e17fb5ca5f");
        for (final String shape : List.of("lshape", "star")) {
            for (final TextureAtlasRotationMode rotation : TextureAtlasRotationMode.values()) {
                final var result = new PolygonPack(
                                300, 300, rotation, rotation == TextureAtlasRotationMode.FREE, .7, null)
                        .pack(sources(shape, shape.equals("star") ? 25 : 60), () -> false, ignored -> {});
                final String hash = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(signature(result).getBytes(StandardCharsets.UTF_8)));
                assertEquals(expected.get(shape + "-" + rotation), hash, shape + "/" + rotation);
            }
        }
    }

    static List<SourcePoly> sources(String shape, int count) {
        Random r = new Random(97 + count);
        List<SourcePoly> sources = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double w = 16 + r.nextInt(65), h = 16 + r.nextInt(65);
            double[][] pts;
            if (shape.equals("star")) {
                pts = new double[10][2];
                for (int j = 0; j < pts.length; j++) {
                    double radius = (j % 2 == 0 ? 1 : .45) * w / 2;
                    pts[j] = new double[] {
                        w / 2 + radius * Math.cos(j * Math.PI / 5), w / 2 + radius * Math.sin(j * Math.PI / 5)
                    };
                }
            } else pts = new double[][] {{0, 0}, {w, 0}, {w, h / 2}, {w / 2, h / 2}, {w / 2, h}, {0, h}};
            double[][] buffered = new double[pts.length][2];
            double cy = shape.equals("star") ? w / 2 : h / 2;
            for (int j = 0; j < pts.length; j++) {
                buffered[j][0] = w / 2 + (pts[j][0] - w / 2) * 1.05;
                buffered[j][1] = cy + (pts[j][1] - cy) * 1.05;
            }
            sources.add(SourcePoly.builder(i, "item-" + i, pts, buffered).build());
        }
        return sources;
    }

    static String signature(PolygonPack.Result result) {
        StringBuilder s = new StringBuilder();
        for (var o : result.outcomes)
            s.append(o.id)
                    .append(':')
                    .append(Arrays.toString(o.cosSin))
                    .append(Arrays.toString(o.trans))
                    .append(';');
        return s.append(result.unplacedIds).toString();
    }
}
