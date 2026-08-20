package dev.turboism.plugin.uitheme.b1.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Deterministic bounded ZIP transport for one theme package. */
public final class ThemePackageArchive {

    public static final int MAX_ARCHIVE_BYTES = 1_048_576;
    private static final int MAX_ENTRIES = 5;
    private static final int MAX_ENTRY_BYTES = 524_288;

    private ThemePackageArchive() {
    }

    /**
     * Encodes a theme into a byte-for-byte reproducible ZIP archive.
     *
     * <p>Determinism is the point: entries are sorted by name and every timestamp is zeroed, so
     * the same theme always yields identical bytes and archives can be compared or hashed.
     *
     * @param theme the theme to package; must not be null
     * @return the archive bytes, at most {@link #MAX_ARCHIVE_BYTES}
     * @throws IllegalArgumentException if the encoded archive would exceed
     *                                  {@link #MAX_ARCHIVE_BYTES}
     * @throws IllegalStateException if writing the in-memory archive fails
     * @throws NullPointerException if {@code theme} is null
     */
    public static byte[] encode(final ThemePackageData theme) {
        Objects.requireNonNull(theme, "theme");
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream output = new ZipOutputStream(bytes)) {
                final List<Map.Entry<String, byte[]>> entries = new ArrayList<>(
                    ThemePackageCodec.encodeZip(theme).entrySet()
                );
                entries.sort(Map.Entry.comparingByKey());
                for (Map.Entry<String, byte[]> entry : entries) {
                    final ZipEntry zip = new ZipEntry(entry.getKey());
                    zip.setTime(0L);
                    output.putNextEntry(zip);
                    output.write(entry.getValue());
                    output.closeEntry();
                }
            }
            final byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_ARCHIVE_BYTES) {
                throw new IllegalArgumentException("theme archive exceeds size limit");
            }
            return encoded;
        } catch (IOException exception) {
            throw new IllegalStateException("could not encode theme archive", exception);
        }
    }

    /**
     * Decodes an archive produced by {@link #encode}, refusing anything outside the transport's
     * bounds.
     *
     * <p>Untrusted input is handled by reporting, not throwing: an oversized archive, a directory
     * entry, more than five entries, an entry over 512 KiB, malformed ZIP data, or a payload the
     * package codec rejects all come back as an invalid result with an issue code. The entry
     * limits are checked while reading, so a zip bomb is refused before it is fully expanded.
     * Entries are sorted by name before decoding, so archive order does not affect the outcome.
     *
     * @param archive the raw archive bytes; must not be null
     * @return a valid result carrying the theme, or an invalid one carrying one of
     *         {@code ARCHIVE_TOO_LARGE}, {@code ARCHIVE_ENTRY_LIMIT},
     *         {@code ARCHIVE_ENTRY_TOO_LARGE}, {@code ARCHIVE_INVALID} or {@code PACKAGE_INVALID}
     * @throws NullPointerException if {@code archive} is null
     */
    public static DecodeResult decode(final byte[] archive) {
        Objects.requireNonNull(archive, "archive");
        if (archive.length > MAX_ARCHIVE_BYTES) {
            return DecodeResult.invalid("ARCHIVE_TOO_LARGE");
        }
        final List<ThemePackageEntry> entries = new ArrayList<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory() || entries.size() == MAX_ENTRIES) {
                    return DecodeResult.invalid("ARCHIVE_ENTRY_LIMIT");
                }
                final byte[] content = input.readNBytes(MAX_ENTRY_BYTES + 1);
                if (content.length > MAX_ENTRY_BYTES) {
                    return DecodeResult.invalid("ARCHIVE_ENTRY_TOO_LARGE");
                }
                entries.add(new ThemePackageEntry(entry.getName(), content));
                input.closeEntry();
            }
        } catch (IOException exception) {
            return DecodeResult.invalid("ARCHIVE_INVALID");
        }
        entries.sort(Comparator.comparing(ThemePackageEntry::name));
        final ThemePackageCodec.DecodeResult decoded = ThemePackageCodec.decode(entries);
        return decoded.valid()
            ? new DecodeResult(decoded.theme(), Optional.empty())
            : DecodeResult.invalid("PACKAGE_INVALID");
    }

    /**
     * The outcome of decoding one archive: exactly one of a theme or an issue code.
     *
     * @param theme the decoded theme, present only on success; must not be null
     * @param issueCode the reason decoding failed, present only on failure; must not be null
     * @throws IllegalArgumentException if both or neither are present
     */
    public record DecodeResult(Optional<ThemePackageData> theme, Optional<String> issueCode) {
        public DecodeResult {
            theme = Objects.requireNonNull(theme, "theme");
            issueCode = Objects.requireNonNull(issueCode, "issueCode");
            if (theme.isPresent() == issueCode.isPresent()) {
                throw new IllegalArgumentException("archive result must contain exactly one value or issue");
            }
        }

        /**
         * @return whether decoding succeeded, in which case {@link #theme()} is present and
         *         {@link #issueCode()} is empty
         */
        public boolean valid() {
            return theme.isPresent();
        }

        private static DecodeResult invalid(final String issueCode) {
            return new DecodeResult(Optional.empty(), Optional.of(issueCode));
        }
    }
}
