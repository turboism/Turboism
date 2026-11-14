package dev.turboism.validation.externalpsd;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Structural validation and deterministic byte mutation for the exported PSD fixture.
 *
 * <p>The probe must prove the native writer produced a structurally valid document before it
 * compares pixel content. Every section walk here fails closed with the exact byte offset, so a
 * malformed export is reported instead of being read as a content mismatch.</p>
 */
final class PsdStructureInspection {
    static String sha256(final byte[] bytes) throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        final StringBuilder hex = new StringBuilder();
        for (final byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /**
     * Returns a copy of {@code psd} with one ASCII letter substituted into a layer's Pascal
     * name, deterministic per {@code cycle}. The file stays structurally valid: same length,
     * same layout, only name bytes change. Returns empty when no mutable name exists.
     */
    static Optional<byte[]> mutateLayerName(final byte[] psd, final int cycle) {
        return mutationFor(psd, cycle).map(mutation -> applyMutation(psd, mutation));
    }

    /** Deterministic per-cycle mutation coordinates over the parsed layer-name table. */
    static Optional<Mutation> mutationFor(final byte[] psd, final int cycle) {
        final List<int[]> names = layerNameRanges(psd);
        if (names.isEmpty()) return Optional.empty();
        final int layer = (cycle - 1) % names.size();
        final int[] range = names.get(layer);
        final int nameOffset = range[1] > 1 ? (cycle - 1) / names.size() % range[1] : 0;
        byte replacement = (byte) ('a' + (cycle - 1) % 26);
        if (replacement == psd[range[0] + nameOffset]) {
            replacement = (byte) (replacement == 'a' ? 'b' : 'a');
        }
        return Optional.of(new Mutation(layer, nameOffset, (char) replacement));
    }

    static byte[] applyMutation(final byte[] psd, final Mutation mutation) {
        final int[] range = layerNameRanges(psd).get(mutation.layer());
        final byte[] copy = psd.clone();
        copy[range[0] + mutation.nameOffset()] = (byte) mutation.letter();
        return copy;
    }

    /** (layerIndex, byteOffsetWithinName, letter) of one name-byte substitution. */
    record Mutation(int layer, int nameOffset, char letter) {
    }

    /** (nameByteStart, nameByteLength) per layer record; empty on any structural anomaly. */
    static List<int[]> layerNameRanges(final byte[] psd) {
        final List<int[]> names = new ArrayList<>();
        try {
            if (psd.length < 30 || psd[0] != '8' || psd[1] != 'B'
                || psd[2] != 'P' || psd[3] != 'S') return names;
            final ByteBuffer buffer = ByteBuffer.wrap(psd).order(ByteOrder.BIG_ENDIAN);
            buffer.position(26);
            buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
            buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
            final int layerMaskStart = buffer.position();
            final int layerMaskLength = buffer.getInt();
            if (layerMaskLength < 4) return names;
            buffer.position(layerMaskStart + 4);
            final int layerInfoLength = buffer.getInt();
            if (layerInfoLength < 2) return names;
            int count = Math.abs(buffer.getShort());
            for (int layer = 0; layer < count && buffer.remaining() > 0; layer++) {
                buffer.position(buffer.position() + 16);
                final int channels = Short.toUnsignedInt(buffer.getShort());
                buffer.position(buffer.position() + channels * 6);
                buffer.position(buffer.position() + 12); // blend sig+key, opacity, clipping, flags, filler
                final int extraStart = buffer.position();
                final int extraLength = buffer.getInt();
                final int extraDataStart = extraStart + 4;
                buffer.position(extraDataStart);
                final int maskLength = buffer.getInt();
                buffer.position(buffer.position() + maskLength);
                final int rangesLength = buffer.getInt();
                buffer.position(buffer.position() + rangesLength);
                final int nameStart = buffer.position();
                final int nameLength = Byte.toUnsignedInt(buffer.get());
                if (nameLength > 0) names.add(new int[] {nameStart + 1, nameLength});
                // All layer records are contiguous; channel image data follows the last record.
                buffer.position(extraDataStart + extraLength);
            }
        } catch (RuntimeException malformed) {
            names.clear();
        }
        return names;
    }

    /**
     * Checks PSD section and channel boundaries without decoding or comparing any pixel sample.
     * This is the GUI session-file readiness gate; it deliberately supports only the observed
     * RGB PSD forms (8-bit raw or PackBits/RLE) and fails closed for other formats.
     */
    static PsdStructureValidation inspectPsdStructure(final byte[] psd) {
        if (psd == null) return PsdStructureValidation.invalid(
            "file", 0, "PSD input is null");
        try {
            final StructureCursor file = new StructureCursor(psd, 0, psd.length);
            file.require(26, "header");
            if (!file.ascii(4, "header signature").equals("8BPS")) {
                throw structureInvalid("header", 0, "unsupported PSD signature");
            }
            final int version = file.u16("header version");
            if (version != 1) {
                throw structureInvalid("header", 4,
                    "unsupported PSD version " + version);
            }
            file.skip(6, "header reserved bytes");
            final int channels = file.u16("header channel count");
            final int height = file.u32("header height");
            final int width = file.u32("header width");
            final int depth = file.u16("header depth");
            final int colorMode = file.u16("header color mode");
            if (channels <= 0 || channels > 64) {
                throw structureInvalid("header", 12,
                    "unsupported channel count " + channels);
            }
            if (width <= 0 || height <= 0) {
                throw structureInvalid("header", 14,
                    "non-positive canvas " + width + "x" + height);
            }
            if (depth != 8) {
                throw structureInvalid("header", 22,
                    "unsupported bit depth " + depth + "; expected 8");
            }
            if (colorMode != 3) {
                throw structureInvalid("header", 24,
                    "unsupported color mode " + colorMode + "; expected RGB");
            }

            readStructureSection(file, "color mode data");
            // The section boundary is authoritative; its payload is not used for target choice.
            final StructureSection resources = readStructureSection(file,
                "image resources");
            validateImageResourcesShape(new StructureCursor(psd, resources.start(),
                resources.end()), "image resources");

            final StructureSection layerMask = readStructureSection(file,
                "layer and mask");
            final StructureCursor layerMaskCursor = new StructureCursor(
                psd, layerMask.start(), layerMask.end());
            final StructureSection layerInfo = readStructureSection(layerMaskCursor,
                "layer info");
            final StructureCursor layerInfoCursor = new StructureCursor(
                psd, layerInfo.start(), layerInfo.end());
            final int encodedLayerCount = layerInfoCursor.s16("layer count");
            final int layerCount = Math.abs(encodedLayerCount);
            final List<LayerShape> layers = new ArrayList<>(layerCount);
            for (int layer = 0; layer < layerCount; layer++) {
                final int top = layerInfoCursor.s32("layer " + layer + " top");
                final int left = layerInfoCursor.s32("layer " + layer + " left");
                final int bottom = layerInfoCursor.s32("layer " + layer + " bottom");
                final int right = layerInfoCursor.s32("layer " + layer + " right");
                if (top < 0 || left < 0 || bottom <= top || right <= left
                    || bottom > height || right > width) {
                    throw structureInvalid("layer info", layerInfoCursor.position(),
                        "layer " + layer + " has invalid bounds");
                }
                final int channelCount = layerInfoCursor.u16(
                    "layer " + layer + " channel count");
                if (channelCount > 64) {
                    throw structureInvalid("layer info", layerInfoCursor.position(),
                        "layer " + layer + " has unsupported channel count " + channelCount);
                }
                final List<Integer> channelLengths = new ArrayList<>(channelCount);
                for (int channel = 0; channel < channelCount; channel++) {
                    layerInfoCursor.s16("layer " + layer + " channel " + channel + " id");
                    final int length = layerInfoCursor.u32(
                        "layer " + layer + " channel " + channel + " length");
                    if (length < 2) {
                        throw structureInvalid("layer info", layerInfoCursor.position(),
                            "layer " + layer + " channel " + channel
                                + " is shorter than compression field");
                    }
                    channelLengths.add(length);
                }
                final String signature = layerInfoCursor.ascii(4,
                    "layer " + layer + " blend signature");
                if (!signature.equals("8BIM") && !signature.equals("8B64")) {
                    throw structureInvalid("layer info", layerInfoCursor.position() - 4,
                        "layer " + layer + " has unsupported blend signature " + signature);
                }
                layerInfoCursor.skip(4, "layer " + layer + " blend key");
                layerInfoCursor.skip(4, "layer " + layer + " blend attributes");
                final int extraLength = layerInfoCursor.u32(
                    "layer " + layer + " extra data length");
                final int extraStart = layerInfoCursor.position();
                final int extraEnd = layerInfoCursor.endFor(extraLength,
                    "layer " + layer + " extra data");
                validateLayerExtraShape(new StructureCursor(psd, extraStart, extraEnd),
                    "layer " + layer + " extra data");
                layerInfoCursor.position(extraEnd);
                layers.add(new LayerShape(top, left, bottom, right, channelLengths));
            }
            for (int layer = 0; layer < layers.size(); layer++) {
                final LayerShape shape = layers.get(layer);
                final int layerWidth = shape.right() - shape.left();
                final int layerHeight = shape.bottom() - shape.top();
                for (int channel = 0; channel < shape.channelLengths().size(); channel++) {
                    final int length = shape.channelLengths().get(channel);
                    final int start = layerInfoCursor.position();
                    final int end = layerInfoCursor.endFor(length,
                        "layer " + layer + " channel " + channel + " data");
                    validateChannelShape(new StructureCursor(psd, start, end), layerWidth,
                        layerHeight, depth, "layer " + layer + " channel " + channel);
                    layerInfoCursor.position(end);
                }
            }
            if (layerInfoCursor.remaining() != 0) {
                throw structureInvalid("layer info", layerInfoCursor.position(),
                    "unread layer-info bytes " + layerInfoCursor.remaining());
            }
            layerMaskCursor.position(layerInfo.end());
            final int globalMaskLength = layerMaskCursor.u32("global layer mask length");
            layerMaskCursor.skip(globalMaskLength, "global layer mask data");
            validateAdditionalInfoShape(layerMaskCursor, "global layer additional info");
            if (layerMaskCursor.remaining() != 0) {
                throw structureInvalid("layer and mask", layerMaskCursor.position(),
                    "unread layer/mask bytes " + layerMaskCursor.remaining());
            }

            validateCompositeShape(file, width, height, depth, channels);
            if (file.remaining() != 0) {
                throw structureInvalid("composite", file.position(),
                    "trailing bytes after composite image " + file.remaining());
            }
            return PsdStructureValidation.valid(psd.length, layerCount, file.position());
        } catch (StructureFailure failure) {
            return PsdStructureValidation.invalid(failure.section(), failure.offset(),
                failure.reason());
        } catch (RuntimeException failure) {
            return PsdStructureValidation.invalid("file", -1, failure.toString());
        }
    }

    static StructureSection readStructureSection(final StructureCursor cursor,
        final String section) {
        final int length = cursor.u32(section + " length");
        final int start = cursor.position();
        final int end = cursor.endFor(length, section);
        cursor.position(end);
        return new StructureSection(start, end);
    }

    static void validateImageResourcesShape(final StructureCursor resources,
        final String section) {
        while (resources.remaining() > 0) {
            if (resources.remaining() < 12) {
                throw structureInvalid(section, resources.position(),
                    "truncated resource header");
            }
            final String signature = resources.ascii(4, section + " signature");
            if (!signature.equals("8BIM") && !signature.equals("MeSa")) {
                throw structureInvalid(section, resources.position() - 4,
                    "unsupported resource signature " + signature);
            }
            resources.skip(2, section + " resource id");
            final int nameLength = resources.u8(section + " resource name length");
            final int paddedNameLength = (nameLength + 1 + 1) & ~1;
            resources.skip(paddedNameLength - 1, section + " resource name");
            final int dataLength = resources.u32(section + " resource data length");
            resources.skip(dataLength, section + " resource data");
            if ((dataLength & 1) != 0) resources.skip(1, section + " resource padding");
        }
    }

    static void validateLayerExtraShape(final StructureCursor extra,
        final String section) {
        final int maskLength = extra.u32(section + " mask length");
        extra.skip(maskLength, section + " mask");
        final int blendingRangesLength = extra.u32(section + " blending ranges length");
        extra.skip(blendingRangesLength, section + " blending ranges");
        final int nameLength = extra.u8(section + " name length");
        final int paddedNameLength = (nameLength + 1 + 3) & ~3;
        extra.skip(paddedNameLength - 1, section + " name");
        validateAdditionalInfoShape(extra, section + " additional info");
        if (extra.remaining() != 0) {
            throw structureInvalid(section, extra.position(),
                "unread layer-extra bytes " + extra.remaining());
        }
    }

    static void validateAdditionalInfoShape(final StructureCursor section,
        final String label) {
        while (section.remaining() > 0) {
            if (section.remaining() < 12) {
                throw structureInvalid(label, section.position(),
                    "truncated additional-info header");
            }
            final String signature = section.ascii(4, label + " signature");
            if (!signature.equals("8BIM") && !signature.equals("8B64")) {
                throw structureInvalid(label, section.position() - 4,
                    "unsupported additional-info signature " + signature);
            }
            section.skip(4, label + " key");
            final int length = section.u32(label + " block length");
            section.skip(length, label + " block");
        }
    }

    static void validateChannelShape(final StructureCursor channel, final int width,
        final int height, final int depth, final String section) {
        final int compression = channel.u16(section + " compression");
        if (compression == 0) {
            final long samples = (long) width * height * (depth / 8);
            if (samples > Integer.MAX_VALUE) {
                throw structureInvalid(section, channel.position(), "raw channel is too large");
            }
            channel.skip((int) samples, section + " raw samples");
            if (channel.remaining() != 0) {
                throw structureInvalid(section, channel.position(),
                    "raw channel has unread bytes " + channel.remaining());
            }
            return;
        }
        if (compression != 1) {
            throw structureInvalid(section, channel.position() - 2,
                "unsupported compression " + compression + "; expected raw or RLE1");
        }
        validateRleShape(channel, width, height, section);
    }

    static void validateCompositeShape(final StructureCursor file, final int width,
        final int height, final int depth, final int channels) {
        final String section = "composite";
        final int compression = file.u16(section + " compression");
        if (compression == 0) {
            final long samples = (long) width * height * channels * (depth / 8);
            if (samples > Integer.MAX_VALUE) {
                throw structureInvalid(section, file.position(), "raw composite is too large");
            }
            file.skip((int) samples, section + " raw samples");
            if (file.remaining() != 0) {
                throw structureInvalid(section, file.position(),
                    "raw composite has unread bytes " + file.remaining());
            }
            return;
        }
        if (compression != 1) {
            throw structureInvalid(section, file.position() - 2,
                "unsupported compression " + compression + "; expected raw or RLE1");
        }
        validateRleShape(file, width, channels * height, section);
    }

    static void validateRleShape(final StructureCursor channel, final int width,
        final int rows, final String section) {
        if (rows <= 0) throw structureInvalid(section, channel.position(), "no RLE rows");
        if (rows > Integer.MAX_VALUE / 2) {
            throw structureInvalid(section, channel.position(), "RLE row table is too large");
        }
        channel.require(rows * 2, section + " row-length table");
        final int[] lengths = new int[rows];
        for (int row = 0; row < rows; row++) lengths[row] = channel.u16(
            section + " row " + row + " length");
        for (int row = 0; row < rows; row++) {
            final int start = channel.position();
            final int end = channel.endFor(lengths[row], section + " row " + row + " data");
            validatePackBitsRow(channel.bytes(), start, end, width,
                section + " row " + row);
            channel.position(end);
        }
        if (channel.remaining() != 0) {
            throw structureInvalid(section, channel.position(),
                "unread RLE bytes " + channel.remaining());
        }
    }

    static void validatePackBitsRow(final byte[] bytes, final int start,
        final int end, final int expectedSamples, final String section) {
        int position = start;
        int decoded = 0;
        while (position < end) {
            final int control = bytes[position++] & 0xff;
            if (control == 0x80) continue;
            final int count;
            if (control <= 0x7f) {
                count = control + 1;
                if (count > end - position) {
                    throw structureInvalid(section, position,
                        "literal packet overruns row");
                }
                position += count;
            } else {
                count = 257 - control;
                if (position >= end) {
                    throw structureInvalid(section, position,
                        "repeat packet has no sample");
                }
                position++;
            }
            if (count > expectedSamples - decoded) {
                throw structureInvalid(section, position,
                    "packet decodes beyond row width");
            }
            decoded += count;
        }
        if (decoded != expectedSamples) {
            throw structureInvalid(section, end,
                "decoded " + decoded + " samples; expected " + expectedSamples);
        }
    }

    static StructureFailure structureInvalid(final String section, final int offset,
        final String reason) {
        return new StructureFailure(section, offset, reason);
    }

    /** SHA-256 over the PSD image-data section (everything after the layer-and-mask record). */
    static String imageDataSha256(final byte[] psd) throws Exception {
        final ByteBuffer buffer = ByteBuffer.wrap(psd).order(ByteOrder.BIG_ENDIAN);
        if (psd.length < 30 || psd[0] != '8' || psd[1] != 'B'
            || psd[2] != 'P' || psd[3] != 'S') {
            throw new IllegalStateException("not a PSD: cannot locate image data section");
        }
        buffer.position(26);
        buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
        buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
        buffer.position(buffer.position() + 4 + buffer.getInt(buffer.position()));
        return sha256(java.util.Arrays.copyOfRange(psd, buffer.position(), psd.length));
    }

    static String sha256(final Path path) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    record PsdStructureValidation(boolean complete, int bytes, int layerCount,
        int compositeEnd, String section, int offset, String reason) {
        PsdStructureValidation {
            section = section == null ? "file" : section;
            reason = reason == null ? "" : reason;
        }

        static PsdStructureValidation valid(final int bytes, final int layerCount,
            final int compositeEnd) {
            return new PsdStructureValidation(true, bytes, layerCount, compositeEnd,
                "complete", -1, "complete PSD section/channel/composite boundaries");
        }

        static PsdStructureValidation invalid(final String section, final int offset,
            final String reason) {
            return new PsdStructureValidation(false, -1, -1, -1, section, offset, reason);
        }
    }

    record StructureSection(int start, int end) {
    }

    record LayerShape(int top, int left, int bottom, int right,
        List<Integer> channelLengths) {
        LayerShape {
            channelLengths = List.copyOf(channelLengths);
        }
    }

    static final class StructureFailure extends RuntimeException {
        static final long serialVersionUID = 1L;
        final String section;
        final int offset;
        final String reason;

        StructureFailure(final String section, final int offset, final String reason) {
            super(section + " at offset " + offset + ": " + reason);
            this.section = section;
            this.offset = offset;
            this.reason = reason;
        }

        String section() { return section; }
        int offset() { return offset; }
        String reason() { return reason; }
    }

    static final class StructureCursor {
        final byte[] bytes;
        final int limit;
        int position;

        StructureCursor(final byte[] bytes, final int start, final int limit) {
            if (bytes == null || start < 0 || limit < start || limit > bytes.length) {
                throw new IllegalArgumentException("invalid PSD structure cursor");
            }
            this.bytes = bytes;
            this.position = start;
            this.limit = limit;
        }

        byte[] bytes() { return bytes; }
        int position() { return position; }
        int remaining() { return limit - position; }

        void position(final int next) {
            if (next < 0 || next > limit) {
                throw structureInvalid("file", position,
                    "cursor position escaped section: " + next);
            }
            position = next;
        }

        void require(final int count, final String section) {
            if (count < 0 || count > remaining()) {
                throw structureInvalid(section, position,
                    "truncated; need " + count + " bytes, remaining " + remaining());
            }
        }

        int endFor(final int length, final String section) {
            if (length < 0 || length > remaining()) {
                throw structureInvalid(section, position,
                    "declared length " + length + " exceeds remaining " + remaining());
            }
            return position + length;
        }

        void skip(final int count, final String section) {
            require(count, section);
            position += count;
        }

        int u8(final String section) {
            require(1, section);
            return bytes[position++] & 0xff;
        }

        int u16(final String section) {
            require(2, section);
            final int value = ((bytes[position] & 0xff) << 8)
                | (bytes[position + 1] & 0xff);
            position += 2;
            return value;
        }

        int s16(final String section) { return (short) u16(section); }

        int s32(final String section) {
            require(4, section);
            final int value = ((bytes[position] & 0xff) << 24)
                | ((bytes[position + 1] & 0xff) << 16)
                | ((bytes[position + 2] & 0xff) << 8)
                | (bytes[position + 3] & 0xff);
            position += 4;
            return value;
        }

        int u32(final String section) {
            final int value = s32(section);
            if (value < 0) {
                throw structureInvalid(section, position - 4,
                    "unsigned 32-bit length exceeds supported bounds");
            }
            return value;
        }

        String ascii(final int length, final String section) {
            require(length, section);
            final String value = new String(bytes, position, length,
                StandardCharsets.US_ASCII);
            position += length;
            return value;
        }
    }
}
