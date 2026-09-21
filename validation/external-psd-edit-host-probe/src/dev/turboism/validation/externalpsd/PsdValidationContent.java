package dev.turboism.validation.externalpsd;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validation-only content mutation for the known external-edit PSD fixture profile.
 *
 * <p>This is intentionally a small profile parser, not a PSD library. It accepts only PSD v1,
 * RGB, 8-bit, four-channel files. The default profile requires a 1000x1000 canvas, seven layers,
 * the fixture's four-channel layout, and PackBits/RLE1 data in every layer and the composite.
 * The default target is
 * located by its validated {@code (450,450)-(550,550)} bounds and 100x100 dimensions; layer index
 * 6 is also required so an index-only mutation cannot silently select another layer. The explicit
 * F1 profile instead requires 2048x2048, twenty paint layers and four group records, and the
 * reviewed per-record layer IDs/group markers; its target is record/layer ID 22. These profiles
 * are validation-only and never control product import admission.</p>
 *
 * <p>The composite is parsed for structural evidence but is never mutated and is not included in
 * the target fingerprint. The fingerprint includes validated target bounds, dimensions, RGB
 * channel IDs, and decoded target RGB content, so a layer-name or composite-only change does not
 * masquerade as a target content change.</p>
 */
public final class PsdValidationContent {
    private static final int PSD_HEADER_LENGTH = 26;
    private static final int EXPECTED_CHANNEL_COUNT = 4;
    private static final int EXPECTED_DEPTH = 8;
    private static final int EXPECTED_COLOR_MODE_RGB = 3;
    private static final int EXPECTED_RLE1 = 1;
    private static final int[] EXPECTED_CHANNEL_IDS = {0, 1, 2, -1};
    private static final byte[] PSD_SIGNATURE = ascii("8BPS");

    /** Explicit validation inputs; the default remains the original seven-layer control. */
    public enum Profile {
        SEVEN_LAYER_CONTROL(1000, 7, 6, new Bounds(450, 450, 550, 550)),
        F1_2048_20(2048, 24, 22, new Bounds(0, 0, 2048, 2048));

        private final int canvas;
        private final int records;
        private final int targetIndex;
        private final Bounds targetBounds;

        Profile(final int canvas, final int records, final int targetIndex,
            final Bounds targetBounds) {
            this.canvas = canvas;
            this.records = records;
            this.targetIndex = targetIndex;
            this.targetBounds = targetBounds;
        }

        private int groupType(final int index) {
            if (this != F1_2048_20) return 0;
            return switch (index) {
                case 0, 12 -> 3;
                case 11, 23 -> 2;
                default -> 0;
            };
        }
    }

    private PsdValidationContent() { }

    /**
     * Returns the SHA-256 content fingerprint of the validated target layer's decoded RGB content.
     *
     * @param psd complete PSD bytes
     * @return target bounds, dimensions, RGB channel IDs, and decoded content SHA-256 in lower-case
     *     hexadecimal
     * @throws ValidationException if the bytes are malformed or outside the supported profile
     */
    public static Fingerprint targetLayerRgbFingerprint(final byte[] psd) {
        return targetLayerRgbFingerprint(psd, Profile.SEVEN_LAYER_CONTROL);
    }

    /** Fingerprints a target identified by the explicit validation profile. */
    public static Fingerprint targetLayerRgbFingerprint(final byte[] psd, final Profile profile) {
        return fingerprint(parse(psd, profile));
    }

    /**
     * Returns a clone with only target-layer RGB PackBits sample bytes XORed with {@code 0xff}.
     * All parsing, section validation, and target identification happen before the clone is
     * created, so malformed input cannot produce a partial mutation.
     *
     * @param psd complete PSD bytes
     * @return mutated clone; the caller's array is never changed
     * @throws ValidationException if the bytes are malformed or outside the supported profile
     */
    public static byte[] invertTargetLayerRgb(final byte[] psd) {
        return invertTargetLayerRgb(psd, Profile.SEVEN_LAYER_CONTROL);
    }

    /** Mutates only the target RGB samples after the complete profile has been validated. */
    public static byte[] invertTargetLayerRgb(final byte[] psd, final Profile profile) {
        final ParsedDocument document = parse(psd, profile);
        final byte[] mutated = psd.clone();
        for (final int sampleOffset : document.targetRgbSampleOffsets) {
            mutated[sampleOffset] ^= (byte) 0xff;
        }
        return mutated;
    }

    /** Structural description returned alongside the target content digest. */
    public record Fingerprint(String sha256, Bounds bounds, int width, int height,
        List<Integer> channelIds) {
        public Fingerprint {
            if (sha256 == null || sha256.isBlank()) {
                throw new IllegalArgumentException("sha256 must not be blank");
            }
            if (bounds == null) throw new IllegalArgumentException("bounds must not be null");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("fingerprint dimensions must be positive");
            }
            if (channelIds == null || channelIds.isEmpty()) {
                throw new IllegalArgumentException("channelIds must not be empty");
            }
            channelIds = List.copyOf(channelIds);
        }
    }

    /** PSD layer bounds in top, left, bottom, right order. */
    public record Bounds(int top, int left, int bottom, int right) {
        public int width() { return right - left; }
        public int height() { return bottom - top; }
    }

    /** Explicit fail-closed diagnostic for malformed or unsupported validation input. */
    public static final class ValidationException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public ValidationException(final String message) { super(message); }
    }

    private static ParsedDocument parse(final byte[] psd, final Profile profile) {
        if (profile == null) throw invalid("validation profile is required");
        if (psd == null) throw invalid("PSD input is null");
        final Cursor file = new Cursor(psd, 0, psd.length);
        file.require(PSD_HEADER_LENGTH, "PSD header");
        for (int index = 0; index < PSD_SIGNATURE.length; index++) {
            if (file.bytes[file.pos + index] != PSD_SIGNATURE[index]) {
                throw invalid("unsupported PSD signature at byte %d", index);
            }
        }
        file.skip(4, "PSD signature");
        final int version = file.u16("PSD version");
        if (version != 1) throw invalid("unsupported PSD version %d; expected 1", version);
        for (int index = 0; index < 6; index++) {
            if (file.u8("PSD reserved byte") != 0) {
                throw invalid("PSD reserved bytes must be zero");
            }
        }
        final int channels = file.u16("PSD channel count");
        final int height = checkedDimension(file.u32("PSD height"), "height");
        final int width = checkedDimension(file.u32("PSD width"), "width");
        final int depth = file.u16("PSD depth");
        final int colorMode = file.u16("PSD color mode");
        if (channels != EXPECTED_CHANNEL_COUNT) {
            throw invalid("unsupported PSD channel count %d; expected %d", channels,
                EXPECTED_CHANNEL_COUNT);
        }
        if (width != profile.canvas || height != profile.canvas) {
            throw invalid("unsupported canvas %dx%d; expected %dx%d", width, height,
                profile.canvas, profile.canvas);
        }
        if (depth != EXPECTED_DEPTH) {
            throw invalid("unsupported PSD depth %d; expected %d", depth, EXPECTED_DEPTH);
        }
        if (colorMode != EXPECTED_COLOR_MODE_RGB) {
            throw invalid("unsupported PSD color mode %d; expected RGB", colorMode);
        }

        skipSection(file, "color mode data");
        final int resourcesStart = file.pos;
        final int resourcesLength = readLength(file, "image resources section");
        final int resourcesEnd = checkedEnd(resourcesStart + 4, resourcesLength,
            file.limit, "image resources section");
        validateImageResources(new Cursor(psd, resourcesStart + 4, resourcesEnd),
            "image resources");
        file.pos = resourcesEnd;

        final int layerMaskStart = file.pos;
        final int layerMaskLength = readLength(file, "layer and mask section");
        final int layerMaskEnd = checkedEnd(layerMaskStart + 4, layerMaskLength,
            file.limit, "layer and mask section");
        final Cursor layerMask = new Cursor(psd, layerMaskStart + 4, layerMaskEnd);
        final int layerInfoStart = layerMask.pos;
        final int layerInfoLength = readLength(layerMask, "layer info section");
        final int layerInfoEnd = checkedEnd(layerInfoStart + 4, layerInfoLength,
            layerMask.limit, "layer info section");
        final List<Layer> layers = parseLayerInfo(
            new Cursor(psd, layerInfoStart + 4, layerInfoEnd), width, height, profile);
        layerMask.pos = layerInfoEnd;

        final int globalMaskLength = readLength(layerMask, "global layer mask data");
        layerMask.skip(globalMaskLength, "global layer mask data");
        validateAdditionalInfo(layerMask, "global layer additional info");
        if (layerMask.pos != layerMask.limit) {
            throw invalid("layer and mask section has an unread tail at byte %d", layerMask.pos);
        }
        file.pos = layerMaskEnd;

        validateComposite(file, width, height, channels);
        if (file.pos != file.limit) {
            throw invalid("trailing bytes after composite image at byte %d", file.pos);
        }

        Layer target = null;
        for (final Layer layer : layers) {
            final int expectedSize = profile == Profile.SEVEN_LAYER_CONTROL
                ? (layer.index == 0 ? profile.canvas : 100)
                : (profile.groupType(layer.index) == 0 ? profile.canvas : 0);
            if (layer.width() != expectedSize || layer.height() != expectedSize) {
                throw invalid("layer %d has %dx%d; expected %dx%d", layer.index,
                    layer.width(), layer.height(), expectedSize, expectedSize);
            }
            if (layer.index == profile.targetIndex && profile.targetBounds.equals(layer.bounds())) {
                if (target != null) throw invalid("target bounds occur more than once");
                target = layer;
            }
        }
        if (target == null) {
            throw invalid("target layer %d with bounds %s was not uniquely identified",
                profile.targetIndex, profile.targetBounds);
        }
        for (final Layer layer : layers) {
            if (profile == Profile.SEVEN_LAYER_CONTROL
                && profile.targetBounds.equals(layer.bounds()) && layer != target) {
                throw invalid("target bounds occur on an unexpected layer index %d", layer.index);
            }
        }

        final List<Integer> sampleOffsets = new ArrayList<>();
        for (final Channel channel : target.channels) {
            if (channel.id >= 0 && channel.id <= 2) sampleOffsets.addAll(channel.sampleOffsets);
        }
        if (sampleOffsets.isEmpty()) throw invalid("target RGB channels contain no samples");
        return new ParsedDocument(target, sampleOffsets);
    }

    private static List<Layer> parseLayerInfo(final Cursor layer, final int canvasWidth,
        final int canvasHeight, final Profile profile) {
        final int layerCount = layer.s16("layer count");
        if (layerCount != profile.records) {
            throw invalid("unsupported layer count %d; expected %d", layerCount,
                profile.records);
        }
        final List<Layer> layers = new ArrayList<>(layerCount);
        for (int index = 0; index < layerCount; index++) {
            final int top = layer.s32("layer top");
            final int left = layer.s32("layer left");
            final int bottom = layer.s32("layer bottom");
            final int right = layer.s32("layer right");
            final boolean group = profile.groupType(index) != 0;
            if (group && (top != 0 || left != 0 || bottom != 0 || right != 0)) {
                throw invalid("group record %d must have empty bounds", index);
            }
            if (!group && (right <= left || bottom <= top)) {
                throw invalid("layer %d has non-positive bounds (%d,%d)-(%d,%d)", index,
                    left, top, right, bottom);
            }
            if (top < 0 || left < 0 || bottom > canvasHeight || right > canvasWidth) {
                throw invalid("layer %d bounds (%d,%d)-(%d,%d) exceed canvas", index,
                    left, top, right, bottom);
            }
            final int channelCount = layer.u16("layer channel count");
            if (channelCount != EXPECTED_CHANNEL_COUNT) {
                throw invalid("layer %d has %d channels; expected %d", index, channelCount,
                    EXPECTED_CHANNEL_COUNT);
            }
            final List<Channel> channels = new ArrayList<>(channelCount);
            for (int channelIndex = 0; channelIndex < channelCount; channelIndex++) {
                final int id = layer.s16("layer channel id");
                if (id != EXPECTED_CHANNEL_IDS[channelIndex]) {
                    throw invalid("layer %d channel %d has id %d; expected %d", index,
                        channelIndex, id, EXPECTED_CHANNEL_IDS[channelIndex]);
                }
                final int length = readLength(layer,
                    "layer " + index + " channel " + id + " data");
                if (length < 2) {
                    throw invalid("layer %d channel %d data is shorter than compression field",
                        index, id);
                }
                channels.add(new Channel(id, length));
            }
            requireSignature(layer, "8BIM", "layer blend signature");
            // Blend keys are metadata outside this validation helper's pixel profile (the
            // captured fixture contains both ordinary and non-normal blend keys). Validate and
            // preserve the field without making the target selection depend on it.
            layer.skip(4, "layer blend key");
            layer.skip(4, "layer blend attributes"); // opacity, clipping, flags, filler
            final int extraLength = readLength(layer, "layer extra data");
            final int extraStart = layer.pos;
            final int extraEnd = checkedEnd(extraStart, extraLength, layer.limit,
                "layer extra data");
            validateLayerExtra(new Cursor(layer.bytes, extraStart, extraEnd),
                "layer " + index + " extra data", profile, index);
            layer.pos = extraEnd;
            layers.add(new Layer(index, top, left, bottom, right, channels));
        }

        for (final Layer parsedLayer : layers) {
            for (final Channel channel : parsedLayer.channels) {
                final int start = layer.pos;
                final int end = checkedEnd(start, channel.declaredLength, layer.limit,
                    "layer channel data");
                final boolean collect = parsedLayer.index == profile.targetIndex
                    && channel.id >= 0 && channel.id <= 2;
                final byte[] decodedSamples = collect
                    ? new byte[parsedLayer.width() * parsedLayer.height()] : null;
                parseLayerChannel(layer.bytes, start, end, parsedLayer.width(),
                    parsedLayer.height(), parsedLayer.index, channel.id,
                    collect ? channel.sampleOffsets : null, decodedSamples,
                    profile.groupType(parsedLayer.index) != 0);
                channel.decodedSamples = decodedSamples;
                layer.pos = end;
            }
        }
        if (layer.pos != layer.limit) {
            throw invalid("layer info has %d unread bytes", layer.limit - layer.pos);
        }
        return layers;
    }

    private static void validateLayerExtra(final Cursor extra, final String label,
        final Profile profile, final int layerIndex) {
        final int maskLength = readLength(extra, label + " mask");
        extra.skip(maskLength, label + " mask");
        final int blendingRangesLength = readLength(extra, label + " blending ranges");
        extra.skip(blendingRangesLength, label + " blending ranges");
        final int nameLength = extra.u8(label + " name length");
        final int paddedNameLength = align4(1 + nameLength, label + " name");
        extra.skip(paddedNameLength - 1, label + " name");
        if (profile == Profile.F1_2048_20) {
            validateF1LayerInfo(extra, label, layerIndex, profile.groupType(layerIndex));
        } else {
            validateAdditionalInfo(extra, label + " additional info");
        }
        if (extra.pos != extra.limit) {
            throw invalid("%s has an unread tail at byte %d", label, extra.pos);
        }
    }

    private static void validateF1LayerInfo(final Cursor section, final String label,
        final int layerIndex, final int expectedGroupType) {
        Integer layerId = null;
        Integer groupType = null;
        while (section.remaining() > 0) {
            requireSignature(section, "8BIM", label + " additional signature");
            final String key = section.ascii(4, label + " additional key");
            final int length = readLength(section, label + " additional block");
            final int end = checkedEnd(section.pos, length, section.limit, label);
            if (key.equals("lyid")) {
                if (layerId != null || length != 4) throw invalid("invalid F1 layer ID block");
                layerId = section.s32(label + " layer ID");
            } else if (key.equals("lsct")) {
                if (groupType != null || length < 4) throw invalid("invalid F1 group block");
                groupType = section.s32(label + " group type");
            }
            section.pos = end;
        }
        // The reviewed writer assigns these IDs to all 24 records, including the dividers.
        // Names and bounds alone cannot identify a target among F1's full-canvas layers.
        if (layerId == null || layerId != layerIndex) {
            throw invalid("F1 record %d has unexpected layer ID %s", layerIndex, layerId);
        }
        if ((groupType == null ? 0 : groupType) != expectedGroupType) {
            throw invalid("F1 record %d has unexpected group type %s", layerIndex, groupType);
        }
    }

    private static void validateAdditionalInfo(final Cursor section, final String label) {
        while (section.remaining() > 0) {
            if (section.remaining() < 12) {
                throw invalid("%s has a truncated additional-info header at byte %d", label,
                    section.pos);
            }
            final String signature = section.ascii(4, label + " signature");
            if (!signature.equals("8BIM") && !signature.equals("8B64")) {
                throw invalid("%s has unsupported signature %s", label, signature);
            }
            section.skip(4, label + " key");
            final int length = readLength(section, label + " block");
            section.skip(length, label + " block");
        }
    }

    private static void validateImageResources(final Cursor resources, final String label) {
        while (resources.remaining() > 0) {
            if (resources.remaining() < 12) {
                throw invalid("%s has a truncated resource header at byte %d", label,
                    resources.pos);
            }
            final String signature = resources.ascii(4, label + " signature");
            if (!signature.equals("8BIM") && !signature.equals("MeSa")) {
                throw invalid("%s has unsupported signature %s", label, signature);
            }
            resources.skip(2, label + " resource id");
            final int nameLength = resources.u8(label + " resource name length");
            final int paddedNameLength = align2(1 + nameLength, label + " resource name");
            resources.skip(paddedNameLength - 1, label + " resource name");
            final int dataLength = readLength(resources, label + " resource data");
            resources.skip(dataLength, label + " resource data");
            if ((dataLength & 1) != 0) resources.skip(1, label + " resource data padding");
        }
        if (resources.pos != resources.limit) {
            throw invalid("%s has an unread tail at byte %d", label, resources.pos);
        }
    }

    private static void parseLayerChannel(final byte[] bytes, final int start, final int end,
        final int width, final int height, final int layerIndex, final int channelId,
        final List<Integer> sampleOffsets, final byte[] decodedSamples, final boolean group) {
        final Cursor channel = new Cursor(bytes, start, end);
        final int compression = channel.u16("layer " + layerIndex + " channel " + channelId
            + " compression");
        if (group) {
            if (compression != 0 || width != 0 || height != 0 || channel.remaining() != 0) {
                throw invalid("F1 group record %d has nonempty channel data", layerIndex);
            }
            return;
        }
        if (compression != EXPECTED_RLE1) {
            throw invalid("layer %d channel %d uses compression %d; only RLE1 is supported",
                layerIndex, channelId, compression);
        }
        final int[] rowLengths = new int[height];
        for (int row = 0; row < height; row++) {
            rowLengths[row] = channel.u16("layer row length");
        }
        for (int row = 0; row < height; row++) {
            final int rowStart = channel.pos;
            final int rowEnd = checkedEnd(rowStart, rowLengths[row], channel.limit,
                "layer row data");
            decodePackBits(bytes, rowStart, rowEnd, width, sampleOffsets,
                decodedSamples, row * width,
                "layer " + layerIndex + " channel " + channelId + " row " + row);
            channel.pos = rowEnd;
        }
        if (channel.pos != channel.limit) {
            throw invalid("layer %d channel %d has %d unread RLE bytes", layerIndex, channelId,
                channel.limit - channel.pos);
        }
    }

    private static void validateComposite(final Cursor file, final int width, final int height,
        final int channelCount) {
        final int start = file.pos;
        if (file.remaining() < 2) throw invalid("composite image is missing compression");
        final Cursor composite = new Cursor(file.bytes, start, file.limit);
        final int compression = composite.u16("composite compression");
        if (compression != EXPECTED_RLE1) {
            throw invalid("composite uses compression %d; only RLE1 is supported", compression);
        }
        final int[] rowLengths = new int[channelCount * height];
        for (int row = 0; row < rowLengths.length; row++) {
            rowLengths[row] = composite.u16("composite row length");
        }
        for (int channel = 0; channel < channelCount; channel++) {
            for (int row = 0; row < height; row++) {
                final int rowStart = composite.pos;
                final int rowEnd = checkedEnd(rowStart,
                    rowLengths[channel * height + row], composite.limit, "composite row data");
                decodePackBits(file.bytes, rowStart, rowEnd, width, null, null, 0,
                    "composite channel " + channel + " row " + row);
                composite.pos = rowEnd;
            }
        }
        if (composite.pos != composite.limit) {
            throw invalid("composite has %d unread RLE bytes", composite.limit - composite.pos);
        }
        file.pos = composite.pos;
    }

    private static void decodePackBits(final byte[] bytes, final int start, final int end,
        final int expectedSamples, final List<Integer> sampleOffsets,
        final byte[] decodedSamples, final int decodedOffset, final String label) {
        int pos = start;
        int decoded = 0;
        while (pos < end) {
            final int control = bytes[pos++] & 0xff;
            if (control == 0x80) continue; // PackBits no-op.
            if (control <= 0x7f) {
                final int count = control + 1;
                if (count > end - pos) {
                    throw invalid("%s literal packet overruns row", label);
                }
                if (count > expectedSamples - decoded) {
                    throw invalid("%s literal packet decodes beyond row length", label);
                }
                if (sampleOffsets != null) {
                    for (int offset = 0; offset < count; offset++) {
                        sampleOffsets.add(pos + offset);
                    }
                }
                if (decodedSamples != null) {
                    System.arraycopy(bytes, pos, decodedSamples, decodedOffset + decoded, count);
                }
                pos += count;
                decoded += count;
            } else {
                final int count = 257 - control;
                if (pos >= end) throw invalid("%s repeat packet has no sample", label);
                if (count > expectedSamples - decoded) {
                    throw invalid("%s repeat packet decodes beyond row length", label);
                }
                if (sampleOffsets != null) sampleOffsets.add(pos);
                if (decodedSamples != null) {
                    for (int offset = 0; offset < count; offset++) {
                        decodedSamples[decodedOffset + decoded + offset] = bytes[pos];
                    }
                }
                pos++;
                decoded += count;
            }
        }
        if (decoded != expectedSamples) {
            throw invalid("%s decodes %d samples; expected %d", label, decoded,
                expectedSamples);
        }
    }

    private static Fingerprint fingerprint(final ParsedDocument document) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw invalid("SHA-256 is unavailable");
        }
        digest.update(ascii("turboism-validation-content-v1\0"));
        updateInt(digest, document.target.top);
        updateInt(digest, document.target.left);
        updateInt(digest, document.target.bottom);
        updateInt(digest, document.target.right);
        updateInt(digest, document.target.width());
        updateInt(digest, document.target.height());
        updateInt(digest, 3);
        for (final Channel channel : document.target.channels) {
            if (channel.id >= 0 && channel.id <= 2) {
                updateInt(digest, channel.id);
                digest.update(channel.decodedSamples);
            }
        }
        return new Fingerprint(hex(digest.digest()), document.target.bounds(),
            document.target.width(), document.target.height(),
            document.target.channels.stream()
                .filter(channel -> channel.id >= 0 && channel.id <= 2)
                .map(channel -> channel.id).toList());
    }

    private static void updateInt(final MessageDigest digest, final int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder result = new StringBuilder(bytes.length * 2);
        for (final byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static int checkedDimension(final long value, final String label) {
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw invalid("invalid PSD %s %d", label, value);
        }
        return (int) value;
    }

    private static void skipSection(final Cursor file, final String label) {
        final int length = readLength(file, label);
        file.skip(length, label);
    }

    private static int readLength(final Cursor cursor, final String label) {
        final long length = cursor.u32(label + " length");
        if (length > Integer.MAX_VALUE || length > cursor.remaining()) {
            throw invalid("%s length %d exceeds remaining bytes %d", label, length,
                cursor.remaining());
        }
        return (int) length;
    }

    private static int checkedEnd(final int start, final int length, final int limit,
        final String label) {
        if (length < 0 || start < 0 || start > limit - length) {
            throw invalid("%s exceeds section boundary", label);
        }
        return start + length;
    }

    private static int align2(final int value, final String label) {
        if (value < 0 || value > Integer.MAX_VALUE - 1) throw invalid("invalid %s length", label);
        return (value + 1) & ~1;
    }

    private static int align4(final int value, final String label) {
        if (value < 0 || value > Integer.MAX_VALUE - 3) throw invalid("invalid %s length", label);
        return (value + 3) & ~3;
    }

    private static void requireSignature(final Cursor cursor, final String expected,
        final String label) {
        requireAscii(cursor, expected, label);
    }

    private static void requireAscii(final Cursor cursor, final String expected,
        final String label) {
        final String actual = cursor.ascii(expected.length(), label);
        if (!actual.equals(expected)) throw invalid("%s is %s; expected %s", label, actual, expected);
    }

    private static byte[] ascii(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static ValidationException invalid(final String format, final Object... args) {
        return new ValidationException(String.format(Locale.ROOT, format, args));
    }

    private static final class ParsedDocument {
        private final Layer target;
        private final List<Integer> targetRgbSampleOffsets;

        private ParsedDocument(final Layer target, final List<Integer> targetRgbSampleOffsets) {
            this.target = target;
            this.targetRgbSampleOffsets = targetRgbSampleOffsets;
        }
    }

    private static final class Layer {
        private final int index;
        private final int top;
        private final int left;
        private final int bottom;
        private final int right;
        private final List<Channel> channels;

        private Layer(final int index, final int top, final int left, final int bottom,
            final int right, final List<Channel> channels) {
            this.index = index;
            this.top = top;
            this.left = left;
            this.bottom = bottom;
            this.right = right;
            this.channels = channels;
        }

        private int width() { return right - left; }
        private int height() { return bottom - top; }
        private Bounds bounds() { return new Bounds(top, left, bottom, right); }
    }

    private static final class Channel {
        private final int id;
        private final int declaredLength;
        private final List<Integer> sampleOffsets = new ArrayList<>();
        private byte[] decodedSamples;

        private Channel(final int id, final int declaredLength) {
            this.id = id;
            this.declaredLength = declaredLength;
        }
    }

    private static final class Cursor {
        private final byte[] bytes;
        private final int limit;
        private int pos;

        private Cursor(final byte[] bytes, final int start, final int limit) {
            if (bytes == null || start < 0 || limit < start || limit > bytes.length) {
                throw invalid("invalid cursor boundary");
            }
            this.bytes = bytes;
            this.pos = start;
            this.limit = limit;
        }

        private int remaining() { return limit - pos; }

        private void require(final int length, final String label) {
            if (length < 0 || length > remaining()) {
                throw invalid("%s is truncated at byte %d", label, pos);
            }
        }

        private int u8(final String label) {
            require(1, label);
            return bytes[pos++] & 0xff;
        }

        private int u16(final String label) {
            require(2, label);
            final int value = ((bytes[pos] & 0xff) << 8) | (bytes[pos + 1] & 0xff);
            pos += 2;
            return value;
        }

        private int s16(final String label) {
            final int value = u16(label);
            return value >= 0x8000 ? value - 0x10000 : value;
        }

        private long u32(final String label) {
            require(4, label);
            final long value = ((long) (bytes[pos] & 0xff) << 24)
                | ((long) (bytes[pos + 1] & 0xff) << 16)
                | ((long) (bytes[pos + 2] & 0xff) << 8)
                | (bytes[pos + 3] & 0xffL);
            pos += 4;
            return value;
        }

        private int s32(final String label) {
            return (int) u32(label);
        }

        private void skip(final int length, final String label) {
            require(length, label);
            pos += length;
        }

        private String ascii(final int length, final String label) {
            require(length, label);
            final String value = new String(bytes, pos, length, StandardCharsets.US_ASCII);
            pos += length;
            return value;
        }
    }
}
