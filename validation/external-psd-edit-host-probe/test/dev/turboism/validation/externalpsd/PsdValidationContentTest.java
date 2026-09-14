package dev.turboism.validation.externalpsd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline tests for the validation-only PSD content helper.
 *
 * <p>This is deliberately a standalone main: it has no host, SDK, runner, or queue dependency.
 * The optional {@code turboism.validation.externalpsd.sample} property only reads the named PSD
 * into memory; the test never writes it.</p>
 */
public final class PsdValidationContentTest {
    private static final int CANVAS_SIZE = 1000;

    public static void main(final String[] args) throws Exception {
        testMutationAndFingerprint();
        testFingerprintIgnoresNameAndComposite();
        testFingerprintUsesDecodedRgbAndIgnoresAlphaEncoding();
        testMalformedRowsAndPacketsAreRejected();
        testUnsupportedProfileIsRejected();
        testRealSampleIfRequested();
        System.out.println("PASS: PsdValidationContentTest");
    }

    private static void testMutationAndFingerprint() {
        final Fixture fixture = Fixture.valid();
        final PsdValidationContent.Fingerprint before = fingerprint(fixture.bytes);
        assertEquals(450, before.bounds().left(), "target left bound");
        assertEquals(450, before.bounds().top(), "target top bound");
        assertEquals(550, before.bounds().right(), "target right bound");
        assertEquals(550, before.bounds().bottom(), "target bottom bound");
        assertEquals(100, before.width(), "target width");
        assertEquals(100, before.height(), "target height");
        assertEquals(List.of(0, 1, 2), before.channelIds(), "target RGB channel identifiers");

        // The fixture exercises a literal packet, repeat packet, row boundary, and the
        // maximum 128-sample repeat packet in the full-size layer.
        assertEquals(0x00, fixture.bytes[fixture.targetLiteralControlOffset] & 0xff,
            "literal packet control");
        assertEquals(0x9d, fixture.bytes[fixture.targetRepeatControlOffset] & 0xff,
            "repeat packet control");
        assertEquals(0x63, fixture.bytes[fixture.targetFullLiteralControlOffset] & 0xff,
            "full-row literal control");
        assertEquals(0x81, fixture.bytes[fixture.canvasRepeatControlOffset] & 0xff,
            "maximum repeat control");

        final byte[] mutated = PsdValidationContent.invertTargetLayerRgb(fixture.bytes);
        assertEquals(fixture.bytes.length, mutated.length, "mutation preserves file length");
        assertOnlyExpectedBytesChanged(fixture.bytes, mutated, fixture.targetRgbSampleOffsets);
        assertRangeEquals(fixture.bytes, mutated, fixture.targetAlphaStart,
            fixture.targetAlphaStart + fixture.targetAlphaLength, "target alpha");
        assertRangeEquals(fixture.bytes, mutated, fixture.compositeStart,
            fixture.compositeStart + fixture.compositeLength, "composite");

        final PsdValidationContent.Fingerprint after = fingerprint(mutated);
        assertTrue(!before.sha256().equals(after.sha256()), "target fingerprint changes");
        assertEquals(before.bounds(), after.bounds(), "bounds survive mutation");
        assertEquals(before.channelIds(), after.channelIds(),
            "RGB channel identifiers survive mutation");

        // XOR is intentionally its own inverse, including repeat packet sample bytes.
        final byte[] restored = PsdValidationContent.invertTargetLayerRgb(mutated);
        assertArrayEquals(fixture.bytes, restored, "second mutation restores original bytes");
        assertEquals(before.sha256(), fingerprint(restored).sha256(),
            "second mutation restores target fingerprint");
    }

    private static void testFingerprintIgnoresNameAndComposite() {
        final Fixture fixture = Fixture.valid();
        final String original = fingerprint(fixture.bytes).sha256();

        final byte[] renamed = fixture.bytes.clone();
        renamed[fixture.targetNameByteOffset] ^= 0x01;
        assertEquals(original, fingerprint(renamed).sha256(),
            "layer-name-only change is outside target content fingerprint");

        final byte[] compositeChanged = fixture.bytes.clone();
        compositeChanged[fixture.compositeSampleOffset] ^= 0xff;
        assertEquals(original, fingerprint(compositeChanged).sha256(),
            "composite-only change is outside target content fingerprint");

        final byte[] targetChanged = PsdValidationContent.invertTargetLayerRgb(fixture.bytes);
        assertTrue(!original.equals(fingerprint(targetChanged).sha256()),
            "target RGB sample change is inside target content fingerprint");
    }

    private static void testFingerprintUsesDecodedRgbAndIgnoresAlphaEncoding() {
        final Fixture baseline = Fixture.valid();
        final String baselineFingerprint = fingerprint(baseline.bytes).sha256();

        final Fixture equivalentRgb = Fixture.equivalentRgbEncoding();
        assertTrue(!Arrays.equals(baseline.bytes, equivalentRgb.bytes),
            "equivalent RGB fixture uses different RLE bytes");
        assertEquals(baselineFingerprint, fingerprint(equivalentRgb.bytes).sha256(),
            "equivalent repeat/literal/no-op RGB encoding has the same fingerprint");

        final Fixture alphaChanged = Fixture.alphaContentChanged();
        assertTrue(!Arrays.equals(baseline.bytes, alphaChanged.bytes),
            "alpha-only fixture changes bytes");
        assertEquals(baselineFingerprint, fingerprint(alphaChanged.bytes).sha256(),
            "alpha-only content does not affect target RGB fingerprint");

        final Fixture alphaReencoded = Fixture.equivalentAlphaEncoding();
        assertTrue(alphaReencoded.targetAlphaLength != baseline.targetAlphaLength,
            "equivalent alpha encoding changes declared encoded length");
        assertEquals(baselineFingerprint, fingerprint(alphaReencoded.bytes).sha256(),
            "equivalent alpha encoding does not affect target RGB fingerprint");

        final byte[] changedRgb = PsdValidationContent.invertTargetLayerRgb(baseline.bytes);
        assertTrue(!baselineFingerprint.equals(fingerprint(changedRgb).sha256()),
            "decoded target RGB modification changes fingerprint");
    }

    private static void testMalformedRowsAndPacketsAreRejected() {
        final Fixture fixture = Fixture.valid();

        final byte[] truncated = Arrays.copyOf(fixture.bytes, fixture.bytes.length - 1);
        expectReject("truncated file", truncated);

        final byte[] badRowLength = fixture.bytes.clone();
        putU16(badRowLength, fixture.targetFirstRowLengthOffset, 1);
        expectReject("decoded row length", badRowLength);

        final byte[] repeatWithoutSample = fixture.bytes.clone();
        putU16(repeatWithoutSample, fixture.targetRepeatRowLengthOffset, 1);
        expectReject("repeat packet without sample", repeatWithoutSample);

        final byte[] literalOverrun = fixture.bytes.clone();
        literalOverrun[fixture.targetFullLiteralControlOffset] = (byte) 0x7f;
        expectReject("literal packet overrun", literalOverrun);

        final byte[] repeatOverrun = fixture.bytes.clone();
        repeatOverrun[fixture.targetRepeatControlOffset] = (byte) 0x9c;
        expectReject("repeat packet overrun", repeatOverrun);

        final byte[] channelLength = fixture.bytes.clone();
        putU32(channelLength, fixture.targetRedChannelLengthOffset,
            fixture.targetRedChannelLength - 1);
        expectReject("channel length mismatch", channelLength);

        final byte[] compositeRowLength = fixture.bytes.clone();
        putU16(compositeRowLength, fixture.compositeFirstRowLengthOffset, 1);
        expectReject("composite row length", compositeRowLength);

        final byte[] layerSectionBoundary = fixture.bytes.clone();
        putU32(layerSectionBoundary, fixture.layerMaskLengthOffset,
            fixture.layerMaskLength + 1L);
        expectReject("layer section length boundary", layerSectionBoundary);

        final byte[] layerInfoBoundary = fixture.bytes.clone();
        putU32(layerInfoBoundary, fixture.layerInfoLengthOffset,
            fixture.layerInfoLength + 1L);
        expectReject("layer info length boundary", layerInfoBoundary);

        // All rejection paths must fail before exposing a partially mutated clone or changing
        // the caller's source array.
        final byte[] source = fixture.bytes.clone();
        final byte[] invalidSource = source.clone();
        invalidSource[fixture.targetRepeatControlOffset] = (byte) 0x9c;
        final byte[] invalidBefore = invalidSource.clone();
        try {
            PsdValidationContent.invertTargetLayerRgb(invalidSource);
            throw new AssertionError("invalid input unexpectedly mutated");
        } catch (PsdValidationContent.ValidationException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isBlank(),
                "invalid input has a diagnostic");
            assertArrayEquals(invalidBefore, invalidSource, "rejection leaves source unchanged");
        }
    }

    private static void testUnsupportedProfileIsRejected() {
        final Fixture fixture = Fixture.valid();

        final byte[] badSignature = fixture.bytes.clone();
        badSignature[0] = 'X';
        expectReject("PSD signature", badSignature);

        final byte[] badVersion = fixture.bytes.clone();
        putU16(badVersion, 4, 2);
        expectReject("PSD version", badVersion);

        final byte[] rawLayer = fixture.bytes.clone();
        putU16(rawLayer, fixture.targetRedCompressionOffset, 0);
        expectReject("raw layer compression", rawLayer);

        final byte[] zipLayer = fixture.bytes.clone();
        putU16(zipLayer, fixture.targetRedCompressionOffset, 2);
        expectReject("ZIP layer compression", zipLayer);

        final byte[] zipComposite = fixture.bytes.clone();
        putU16(zipComposite, fixture.compositeCompressionOffset, 2);
        expectReject("ZIP composite compression", zipComposite);

        final byte[] sixteenBit = fixture.bytes.clone();
        putU16(sixteenBit, 22, 16);
        expectReject("16-bit profile", sixteenBit);

        final byte[] wrongChannelId = fixture.bytes.clone();
        putU16(wrongChannelId, fixture.targetChannelIdOffset, 3);
        expectReject("target channel layout", wrongChannelId);

        final byte[] wrongBounds = fixture.bytes.clone();
        putU32(wrongBounds, fixture.targetRecordStart + 4, 451);
        expectReject("target bounds", wrongBounds);

        final byte[] wrongChannelCount = fixture.bytes.clone();
        putU16(wrongChannelCount, fixture.targetRecordStart + 16, 3);
        expectReject("target channel count", wrongChannelCount);
    }

    private static void testRealSampleIfRequested() throws IOException {
        final String configuredPath = System.getProperty(
            "turboism.validation.externalpsd.sample", "");
        if (configuredPath.isBlank()) return;
        final Path sample = Path.of(configuredPath);
        final byte[] original = Files.readAllBytes(sample);
        final PsdValidationContent.Fingerprint before = fingerprint(original);
        final byte[] changed = PsdValidationContent.invertTargetLayerRgb(original);
        final PsdValidationContent.Fingerprint after = fingerprint(changed);
        assertTrue(!before.sha256().equals(after.sha256()),
            "real sample target fingerprint changes in memory");
        assertArrayEquals(original, PsdValidationContent.invertTargetLayerRgb(changed),
            "real sample XOR restores in memory");
        System.out.println("PASS: real PSD sample read-only validation " + sample);
    }

    private static PsdValidationContent.Fingerprint fingerprint(final byte[] psd) {
        return PsdValidationContent.targetLayerRgbFingerprint(psd);
    }

    private static void expectReject(final String description, final byte[] candidate) {
        final byte[] before = candidate.clone();
        try {
            PsdValidationContent.invertTargetLayerRgb(candidate);
            throw new AssertionError(description + " unexpectedly accepted");
        } catch (PsdValidationContent.ValidationException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isBlank(),
                description + " has a diagnostic");
            assertArrayEquals(before, candidate, description + " leaves input unchanged");
        }
    }

    private static void assertOnlyExpectedBytesChanged(final byte[] before, final byte[] after,
        final int[] expectedOffsets) {
        final Set<Integer> expected = new HashSet<>();
        for (final int offset : expectedOffsets) expected.add(offset);
        int differences = 0;
        for (int offset = 0; offset < before.length; offset++) {
            if (before[offset] == after[offset]) continue;
            differences++;
            assertTrue(expected.contains(offset), "unexpected changed byte at " + offset);
            assertEquals(before[offset] ^ (byte) 0xff, after[offset],
                "sample byte is bitwise inverted at " + offset);
        }
        assertEquals(expected.size(), differences, "all and only RGB sample bytes changed");
    }

    private static void assertRangeEquals(final byte[] expected, final byte[] actual,
        final int start, final int end, final String description) {
        assertArrayEquals(Arrays.copyOfRange(expected, start, end),
            Arrays.copyOfRange(actual, start, end), description);
    }

    private static void putU16(final byte[] bytes, final int offset, final int value) {
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }

    private static void putU32(final byte[] bytes, final int offset, final long value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static void assertTrue(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(final int expected, final int actual, final String message) {
        if (expected != actual) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertEquals(final String expected, final String actual,
        final String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertEquals(final Object expected, final Object actual,
        final String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertArrayEquals(final byte[] expected, final byte[] actual,
        final String message) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(message);
        }
    }

    private static final class Fixture {
        private final byte[] bytes;
        private final int[] targetRgbSampleOffsets;
        private final int targetNameByteOffset;
        private final int compositeSampleOffset;
        private final int compositeStart;
        private final int compositeLength;
        private final int targetAlphaStart;
        private final int targetAlphaLength;
        private final int targetFirstRowLengthOffset;
        private final int targetRepeatRowLengthOffset;
        private final int targetLiteralControlOffset;
        private final int targetRepeatControlOffset;
        private final int targetFullLiteralControlOffset;
        private final int canvasRepeatControlOffset;
        private final int targetRedCompressionOffset;
        private final int targetRedChannelLengthOffset;
        private final int targetRedChannelLength;
        private final int targetChannelIdOffset;
        private final int targetRecordStart;
        private final int compositeFirstRowLengthOffset;
        private final int compositeCompressionOffset;
        private final int layerMaskLengthOffset;
        private final int layerMaskLength;
        private final int layerInfoLengthOffset;
        private final int layerInfoLength;

        private Fixture(final byte[] bytes, final int[] targetRgbSampleOffsets,
            final int targetNameByteOffset, final int compositeSampleOffset,
            final int compositeStart, final int compositeLength, final int targetAlphaStart,
            final int targetAlphaLength, final int targetFirstRowLengthOffset,
            final int targetRepeatRowLengthOffset,
            final int targetLiteralControlOffset, final int targetRepeatControlOffset,
            final int targetFullLiteralControlOffset, final int canvasRepeatControlOffset,
            final int targetRedCompressionOffset, final int targetRedChannelLengthOffset,
            final int targetRedChannelLength, final int targetChannelIdOffset,
            final int targetRecordStart, final int compositeFirstRowLengthOffset,
            final int compositeCompressionOffset, final int layerMaskLengthOffset,
            final int layerMaskLength, final int layerInfoLengthOffset,
            final int layerInfoLength) {
            this.bytes = bytes;
            this.targetRgbSampleOffsets = targetRgbSampleOffsets;
            this.targetNameByteOffset = targetNameByteOffset;
            this.compositeSampleOffset = compositeSampleOffset;
            this.compositeStart = compositeStart;
            this.compositeLength = compositeLength;
            this.targetAlphaStart = targetAlphaStart;
            this.targetAlphaLength = targetAlphaLength;
            this.targetFirstRowLengthOffset = targetFirstRowLengthOffset;
            this.targetRepeatRowLengthOffset = targetRepeatRowLengthOffset;
            this.targetLiteralControlOffset = targetLiteralControlOffset;
            this.targetRepeatControlOffset = targetRepeatControlOffset;
            this.targetFullLiteralControlOffset = targetFullLiteralControlOffset;
            this.canvasRepeatControlOffset = canvasRepeatControlOffset;
            this.targetRedCompressionOffset = targetRedCompressionOffset;
            this.targetRedChannelLengthOffset = targetRedChannelLengthOffset;
            this.targetRedChannelLength = targetRedChannelLength;
            this.targetChannelIdOffset = targetChannelIdOffset;
            this.targetRecordStart = targetRecordStart;
            this.compositeFirstRowLengthOffset = compositeFirstRowLengthOffset;
            this.compositeCompressionOffset = compositeCompressionOffset;
            this.layerMaskLengthOffset = layerMaskLengthOffset;
            this.layerMaskLength = layerMaskLength;
            this.layerInfoLengthOffset = layerInfoLengthOffset;
            this.layerInfoLength = layerInfoLength;
        }

        private static Fixture valid() {
            return create(false, false, false);
        }

        private static Fixture equivalentRgbEncoding() {
            return create(true, false, false);
        }

        private static Fixture alphaContentChanged() {
            return create(false, false, true);
        }

        private static Fixture equivalentAlphaEncoding() {
            return create(false, true, false);
        }

        private static Fixture create(final boolean equivalentRgb, final boolean equivalentAlpha,
            final boolean alphaChanged) {
            final List<Layer> layers = new ArrayList<>();
            layers.add(Layer.create(0, 0, CANVAS_SIZE, CANVAS_SIZE, "layer0"));
            layers.add(Layer.create(0, 0, 100, 100, "layer1"));
            layers.add(Layer.create(100, 100, 200, 200, "layer2"));
            layers.add(Layer.create(200, 200, 300, 300, "layer3"));
            layers.add(Layer.create(250, 250, 350, 350, "layer4"));
            layers.add(Layer.create(350, 350, 450, 450, "layer5"));
            layers.add(Layer.create(450, 450, 550, 550, "layer6", equivalentRgb,
                equivalentAlpha, alphaChanged));

            final Bytes layerInfo = new Bytes();
            layerInfo.u16(layers.size());
            for (final Layer layer : layers) layerInfo.bytes(layer.record);
            for (final Layer layer : layers) {
                for (final Channel channel : layer.channels) layerInfo.bytes(channel.data);
            }

            final byte[] resources = resourceSection();
            final Composite composite = Composite.create();
            final int outerLength = 4 + layerInfo.size() + 4;

            final Bytes file = new Bytes();
            file.ascii("8BPS");
            file.u16(1);
            file.zeros(6);
            file.u16(4);
            file.u32(CANVAS_SIZE);
            file.u32(CANVAS_SIZE);
            file.u16(8);
            file.u16(3);
            file.u32(0); // color mode data length
            file.u32(resources.length);
            file.bytes(resources);
            final int layerMaskLengthOffset = file.size();
            file.u32(outerLength);
            final int layerInfoLengthOffset = file.size();
            file.u32(layerInfo.size());
            final int layerInfoStart = file.size();
            file.bytes(layerInfo.toByteArray());
            file.u32(0); // global layer mask length
            final int compositeStart = file.size();
            file.bytes(composite.data);
            final byte[] bytes = file.toByteArray();

            final int recordBase = layerInfoStart + 2;
            int recordOffset = recordBase;
            int channelDataBase = recordBase;
            for (final Layer layer : layers) channelDataBase += layer.record.length;
            int dataOffset = channelDataBase;
            int targetRecordStart = -1;
            int targetNameByteOffset = -1;
            int targetChannelIdOffset = -1;
            int targetRedLengthOffset = -1;
            int targetRedStart = -1;
            int targetRedLength = -1;
            int targetFirstRowLengthOffset = -1;
            int targetRepeatRowLengthOffset = -1;
            int targetLiteralControlOffset = -1;
            int targetRepeatControlOffset = -1;
            int targetFullLiteralControlOffset = -1;
            int targetAlphaStart = -1;
            int targetAlphaLength = -1;
            final List<Integer> targetSamples = new ArrayList<>();
            int canvasRepeatControlOffset = -1;
            for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
                final Layer layer = layers.get(layerIndex);
                if (layerIndex == 6) {
                    targetRecordStart = recordOffset;
                    targetNameByteOffset = recordOffset + layer.nameByteOffset;
                }
                for (int channelIndex = 0; channelIndex < layer.channels.size(); channelIndex++) {
                    final Channel channel = layer.channels.get(channelIndex);
                    final int currentDataOffset = dataOffset;
                    if (layerIndex == 0 && channelIndex == 0) {
                        canvasRepeatControlOffset = currentDataOffset + channel.firstControlOffset;
                    }
                    if (layerIndex == 6) {
                        if (channelIndex == 0) {
                            targetChannelIdOffset = recordOffset + channel.idOffset;
                            targetRedLengthOffset = recordOffset + channel.lengthOffset;
                            targetRedStart = currentDataOffset;
                            targetRedLength = channel.data.length;
                            targetFirstRowLengthOffset = currentDataOffset + channel.firstRowLengthOffset;
                            targetRepeatRowLengthOffset = currentDataOffset
                                + channel.repeatRowLengthOffset;
                            targetLiteralControlOffset = currentDataOffset
                                + channel.firstLiteralControlOffset;
                            targetRepeatControlOffset = currentDataOffset
                                + channel.firstRepeatControlOffset;
                            targetFullLiteralControlOffset = currentDataOffset
                                + channel.fullLiteralControlOffset;
                        }
                        if (channelIndex < 3) {
                            for (final int offset : channel.sampleOffsets) {
                                targetSamples.add(currentDataOffset + offset);
                            }
                        } else {
                            targetAlphaStart = currentDataOffset;
                            targetAlphaLength = channel.data.length;
                        }
                    }
                    dataOffset += channel.data.length;
                }
                recordOffset += layer.record.length;
            }

            return new Fixture(bytes, targetSamples.stream().mapToInt(Integer::intValue).toArray(),
                targetNameByteOffset, compositeStart + composite.sampleOffset, compositeStart,
                composite.data.length, targetAlphaStart, targetAlphaLength,
                targetFirstRowLengthOffset, targetRepeatRowLengthOffset,
                targetLiteralControlOffset, targetRepeatControlOffset,
                targetFullLiteralControlOffset, canvasRepeatControlOffset, targetRedStart,
                targetRedLengthOffset, targetRedLength, targetChannelIdOffset, targetRecordStart,
                compositeStart + composite.firstRowLengthOffset,
                compositeStart + composite.compressionOffset, layerMaskLengthOffset, outerLength,
                layerInfoLengthOffset, layerInfo.size());
        }

        private static byte[] resourceSection() {
            final Bytes resources = new Bytes();
            resources.ascii("8BIM");
            resources.u16(0x0400);
            resources.u8(11);
            resources.ascii("TargetLayer");
            resources.u32(2);
            resources.u16(1);
            return resources.toByteArray();
        }
    }

    private static final class Layer {
        private final byte[] record;
        private final List<Channel> channels;
        private final int nameByteOffset;

        private Layer(final byte[] record, final List<Channel> channels,
            final int nameByteOffset) {
            this.record = record;
            this.channels = channels;
            this.nameByteOffset = nameByteOffset;
        }

        private static Layer create(final int top, final int left, final int bottom,
            final int right, final String name) {
            return create(top, left, bottom, right, name, false, false, false);
        }

        private static Layer create(final int top, final int left, final int bottom,
            final int right, final String name, final boolean equivalentRgb,
            final boolean equivalentAlpha, final boolean alphaChanged) {
            final int width = right - left;
            final int height = bottom - top;
            final boolean target = top == 450 && left == 450
                && bottom == 550 && right == 550;
            final List<Channel> channels = List.of(
                Channel.create(0, width, height, target, 0, equivalentRgb, equivalentAlpha,
                    alphaChanged),
                Channel.create(1, width, height, target, 1, equivalentRgb, equivalentAlpha,
                    alphaChanged),
                Channel.create(2, width, height, target, 2, equivalentRgb, equivalentAlpha,
                    alphaChanged),
                Channel.create(-1, width, height, target, 3, equivalentRgb, equivalentAlpha,
                    alphaChanged));
            final Bytes record = new Bytes();
            record.u32(top);
            record.u32(left);
            record.u32(bottom);
            record.u32(right);
            record.u16(channels.size());
            final int[] lengthOffsets = new int[channels.size()];
            final int[] idOffsets = new int[channels.size()];
            for (int index = 0; index < channels.size(); index++) {
                idOffsets[index] = record.size();
                record.u16(channels.get(index).id);
                lengthOffsets[index] = record.size();
                record.u32(0);
            }
            record.ascii("8BIM");
            record.ascii("norm");
            record.u8(255);
            record.u8(0);
            record.u8(0x0a);
            record.u8(0);

            final Bytes extra = new Bytes();
            extra.u32(0); // mask data length
            extra.u32(0); // blending ranges length
            final int nameByteOffsetInExtra = extra.size() + 1;
            final byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
            extra.u8(nameBytes.length);
            extra.bytes(nameBytes);
            final int paddedNameLength = (nameBytes.length + 1 + 3) & ~3;
            extra.zeros(paddedNameLength - nameBytes.length - 1);
            extra.ascii("8BIM");
            extra.ascii("test");
            extra.u32(4);
            extra.ascii("meta");

            final int extraLengthOffset = record.size();
            record.u32(extra.size());
            final int extraStart = record.size();
            record.bytes(extra.toByteArray());
            final byte[] recordBytes = record.toByteArray();
            for (int index = 0; index < channels.size(); index++) {
                putU32(recordBytes, lengthOffsets[index], channels.get(index).data.length);
            }
            // Keep these variables visibly tied to the bytes above; this also catches accidental
            // fixture changes that would make the exposed corruption offsets stale.
            if (extraLengthOffset + 4 != extraStart) {
                throw new AssertionError("fixture extra offset calculation");
            }
            if (idOffsets[0] != 18 || lengthOffsets[0] != 20) {
                throw new AssertionError("fixture channel offset calculation");
            }
            return new Layer(recordBytes, channels, extraStart + nameByteOffsetInExtra);
        }
    }

    private static final class Channel {
        private final int id;
        private final byte[] data;
        private final List<Integer> sampleOffsets;
        private final int idOffset;
        private final int lengthOffset;
        private final int firstRowLengthOffset;
        private final int repeatRowLengthOffset;
        private final int firstControlOffset;
        private final int firstLiteralControlOffset;
        private final int firstRepeatControlOffset;
        private final int fullLiteralControlOffset;

        private Channel(final int id, final byte[] data, final List<Integer> sampleOffsets,
            final int firstRowLengthOffset, final int repeatRowLengthOffset,
            final int firstControlOffset,
            final int firstLiteralControlOffset, final int firstRepeatControlOffset,
            final int fullLiteralControlOffset, final int channelIndex) {
            this.id = id;
            this.data = data;
            this.sampleOffsets = sampleOffsets;
            this.idOffset = 18 + channelIndex * 6;
            this.lengthOffset = 20 + channelIndex * 6;
            this.firstRowLengthOffset = firstRowLengthOffset;
            this.repeatRowLengthOffset = repeatRowLengthOffset;
            this.firstControlOffset = firstControlOffset;
            this.firstLiteralControlOffset = firstLiteralControlOffset;
            this.firstRepeatControlOffset = firstRepeatControlOffset;
            this.fullLiteralControlOffset = fullLiteralControlOffset;
        }

        private static Channel create(final int id, final int width, final int height,
            final boolean target, final int channelIndex, final boolean equivalentRgb,
            final boolean equivalentAlpha, final boolean alphaChanged) {
            final Bytes data = new Bytes();
            data.u16(1);
            final int rowTableOffset = data.size();
            data.zeros(height * 2);
            final List<Integer> sampleOffsets = new ArrayList<>();
            int firstControlOffset = -1;
            int firstLiteralControlOffset = -1;
            int firstRepeatControlOffset = -1;
            int fullLiteralControlOffset = -1;
            for (int row = 0; row < height; row++) {
                final int rowStart = data.size();
                final boolean alternate = target
                    && ((channelIndex < 3 && equivalentRgb)
                    || (channelIndex == 3 && equivalentAlpha));
                if (target && alternate) {
                    firstControlOffset = rowStart;
                    firstLiteralControlOffset = rowStart;
                    final byte[] values = targetRowValues(width, row, channelIndex,
                        alphaChanged);
                    if (row == 0 || row == 1) {
                        encodeLiteral(data, values, 0, values.length, sampleOffsets);
                    } else if (row == 2) {
                        fullLiteralControlOffset = rowStart;
                        encodeLiteral(data, values, 0, 40, sampleOffsets);
                        encodeLiteral(data, values, 40, values.length - 40, sampleOffsets);
                    } else {
                        data.u8(0x80); // legal PackBits no-op, changing encoded length only
                        if (firstRepeatControlOffset < 0) firstRepeatControlOffset = data.size();
                        encodeRepeat(data, values[0] & 0xff, values.length, sampleOffsets);
                    }
                } else if (row == 0 && target) {
                    firstControlOffset = rowStart;
                    firstLiteralControlOffset = rowStart;
                    final byte[] values = targetRowValues(width, row, channelIndex,
                        alphaChanged);
                    data.u8(0);
                    sampleOffsets.add(data.size());
                    data.u8(values[0] & 0xff);
                    data.u8(257 - 99);
                    firstRepeatControlOffset = data.size() - 1;
                    sampleOffsets.add(data.size());
                    data.u8(values[1] & 0xff);
                } else if (row == 1 && target) {
                    firstRepeatControlOffset = rowStart;
                    final byte[] values = targetRowValues(width, row, channelIndex,
                        alphaChanged);
                    encodeRepeat(data, values[0] & 0xff, values.length, sampleOffsets);
                } else if (row == 2 && target) {
                    fullLiteralControlOffset = rowStart;
                    final byte[] values = targetRowValues(width, row, channelIndex,
                        alphaChanged);
                    encodeLiteral(data, values, 0, values.length, sampleOffsets);
                } else if (!target && width == CANVAS_SIZE) {
                    if (row == 0 && channelIndex == 0) firstControlOffset = rowStart;
                    encodeRepeatChunks(data, width, 0x10 + channelIndex, null);
                } else {
                    if (target) {
                        final byte[] values = targetRowValues(width, row, channelIndex,
                            alphaChanged);
                        encodeRepeat(data, values[0] & 0xff, values.length, sampleOffsets);
                    } else {
                        encodeRepeatChunks(data, width, 0x60 + channelIndex, null);
                    }
                }
                final int rowLength = data.size() - rowStart;
                data.patchU16(rowTableOffset + row * 2, rowLength);
            }
            if (target && firstControlOffset < 0) {
                throw new AssertionError("target channel did not encode row zero");
            }
            return new Channel(id, data.toByteArray(), sampleOffsets, rowTableOffset,
                rowTableOffset + 2, firstControlOffset, firstLiteralControlOffset,
                firstRepeatControlOffset,
                fullLiteralControlOffset, channelIndex);
        }

        private static byte[] targetRowValues(final int width, final int row,
            final int channelIndex, final boolean alphaChanged) {
            final byte[] values = new byte[width];
            for (int sample = 0; sample < width; sample++) {
                final int value;
                if (row == 0) {
                    value = sample == 0 ? 0x20 + channelIndex : 0x30 + channelIndex;
                } else if (row == 1) {
                    value = 0x40 + channelIndex;
                } else if (row == 2) {
                    value = 0x50 + channelIndex + (sample & 0x0f);
                } else {
                    value = 0x60 + channelIndex;
                }
                values[sample] = (byte) ((alphaChanged && channelIndex == 3)
                    ? value ^ 0x01 : value);
            }
            return values;
        }

        private static void encodeLiteral(final Bytes data, final byte[] values,
            final int offset, final int length, final List<Integer> sampleOffsets) {
            if (length < 1 || length > 128 || offset < 0 || offset + length > values.length) {
                throw new AssertionError("invalid fixture literal packet");
            }
            data.u8(length - 1);
            for (int sample = offset; sample < offset + length; sample++) {
                sampleOffsets.add(data.size());
                data.u8(values[sample] & 0xff);
            }
        }

        private static void encodeRepeat(final Bytes data, final int value, final int count,
            final List<Integer> sampleOffsets) {
            if (count < 2 || count > 128) {
                throw new AssertionError("invalid fixture repeat packet");
            }
            data.u8(257 - count);
            sampleOffsets.add(data.size());
            data.u8(value);
        }
    }

    private static final class Composite {
        private final byte[] data;
        private final int compressionOffset;
        private final int firstRowLengthOffset;
        private final int sampleOffset;

        private Composite(final byte[] data, final int compressionOffset,
            final int firstRowLengthOffset, final int sampleOffset) {
            this.data = data;
            this.compressionOffset = compressionOffset;
            this.firstRowLengthOffset = firstRowLengthOffset;
            this.sampleOffset = sampleOffset;
        }

        private static Composite create() {
            final Bytes data = new Bytes();
            final int compressionOffset = data.size();
            data.u16(1);
            final int rowTableOffset = data.size();
            data.zeros(4 * CANVAS_SIZE * 2);
            int firstSampleOffset = -1;
            for (int channel = 0; channel < 4; channel++) {
                for (int row = 0; row < CANVAS_SIZE; row++) {
                    final int rowStart = data.size();
                    if (firstSampleOffset < 0) firstSampleOffset = rowStart + 1;
                    encodeRepeatChunks(data, CANVAS_SIZE, 1, null);
                    data.patchU16(
                        rowTableOffset + ((channel * CANVAS_SIZE + row) * 2),
                        data.size() - rowStart);
                }
            }
            return new Composite(data.toByteArray(), compressionOffset, rowTableOffset,
                firstSampleOffset);
        }
    }

    private static void encodeRepeatChunks(final Bytes data, final int width, final int value,
        final List<Integer> ignored) {
        int remaining = width;
        while (remaining > 0) {
            final int count = Math.min(128, remaining);
            if (count == 1) {
                data.u8(0);
                data.u8(value);
            } else {
                data.u8(257 - count);
                if (ignored != null) ignored.add(data.size());
                data.u8(value);
            }
            remaining -= count;
        }
    }

    private static final class Bytes {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private int size() { return output.size(); }

        private void u8(final int value) { output.write(value & 0xff); }

        private void u16(final int value) {
            output.write((value >>> 8) & 0xff);
            output.write(value & 0xff);
        }

        private void u32(final long value) {
            output.write((int) (value >>> 24) & 0xff);
            output.write((int) (value >>> 16) & 0xff);
            output.write((int) (value >>> 8) & 0xff);
            output.write((int) value & 0xff);
        }

        private void ascii(final String value) {
            bytes(value.getBytes(StandardCharsets.US_ASCII));
        }

        private void zeros(final int count) {
            if (count < 0) throw new AssertionError("negative fixture padding");
            for (int index = 0; index < count; index++) output.write(0);
        }

        private void bytes(final byte[] value) {
            output.write(value, 0, value.length);
        }

        private void patchU16(final int offset, final int value) {
            final byte[] current = output.toByteArray();
            putU16(current, offset, value);
            output.reset();
            output.write(current, 0, current.length);
        }

        private byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
