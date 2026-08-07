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

    public record DecodeResult(Optional<ThemePackageData> theme, Optional<String> issueCode) {
        public DecodeResult {
            theme = Objects.requireNonNull(theme, "theme");
            issueCode = Objects.requireNonNull(issueCode, "issueCode");
            if (theme.isPresent() == issueCode.isPresent()) {
                throw new IllegalArgumentException("archive result must contain exactly one value or issue");
            }
        }

        public boolean valid() {
            return theme.isPresent();
        }

        private static DecodeResult invalid(final String issueCode) {
            return new DecodeResult(Optional.empty(), Optional.of(issueCode));
        }
    }
}
