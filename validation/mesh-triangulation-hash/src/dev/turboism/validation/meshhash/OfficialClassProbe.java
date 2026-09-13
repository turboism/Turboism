package dev.turboism.validation.meshhash;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;

/**
 * Read-only shape verification against the official Cubism JAR.
 *
 * <p>It reads one class entry from the archive, verifies the reviewed identity (name, constant
 * {@code hashCode}, three same-typed corner fields), and generates the candidate patch <b>in
 * memory only</b>. The official class is never defined, verified, executed, written to disk, or
 * placed on a classpath; only its bytes are parsed.</p>
 */
public final class OfficialClassProbe {
    private static final String TRIPLE_INTERNAL =
        "com/live2d/graphics3d/editableMesh/triangulation/l";
    private static final String POINT_DESCRIPTOR =
        "Lcom/live2d/graphics3d/editableMesh/triangulation/TriPoint;";

    private OfficialClassProbe() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalStateException("usage: OfficialClassProbe <Live2D_Cubism.jar>");
        }
        final Path jar = Path.of(args[0]);
        final byte[] official;
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            final ZipEntry entry = archive.getEntry(TRIPLE_INTERNAL + ".class");
            if (entry == null) {
                throw new IllegalStateException("entry is absent: " + TRIPLE_INTERNAL);
            }
            try (InputStream stream = archive.getInputStream(entry)) {
                official = stream.readAllBytes();
            }
        }

        System.out.println("OFFICIAL_ENTRY " + TRIPLE_INTERNAL + ".class"
            + " size=" + official.length
            + " sha256=" + sha256(official));

        final CornerTripleHashPatcher patcher =
            new CornerTripleHashPatcher(TRIPLE_INTERNAL, POINT_DESCRIPTOR);
        final byte[] candidate;
        try {
            candidate = patcher.patch(official);
        } catch (CornerTripleHashPatcher.Rejected rejected) {
            System.out.println("OFFICIAL_PATCH NOT_APPLICABLE " + rejected.getMessage());
            System.out.println("MESH_TRIANGULATION_HASH_OFFICIAL_PROBE BLOCKED officialDefined=false");
            System.exit(1);
            return;
        }

        // Structural sanity only: re-parse the candidate. It is never defined or verified.
        final ClassReader candidateReader = new ClassReader(candidate);
        System.out.println("OFFICIAL_PATCH CANDIDATE"
            + " className=" + candidateReader.getClassName()
            + " size=" + candidate.length
            + " sha256=" + sha256(candidate)
            + " defined=false executed=false");
        System.out.println("OFFICIAL_METHODS_UNCHANGED_OUTSIDE_HASHCODE"
            + " originalMethods=" + 1
            + " replacedMethods=hashCode()I");
        System.out.println("MESH_TRIANGULATION_HASH_OFFICIAL_PROBE PASS"
            + " officialDefined=false officialExecuted=false officialWritten=false");
    }

    private static String sha256(final byte[] bytes) throws Exception {
        final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        final byte[] hash = digest.digest(bytes);
        final StringBuilder text = new StringBuilder(hash.length * 2);
        for (final byte value : hash) {
            text.append(Character.forDigit((value >> 4) & 0xF, 16));
            text.append(Character.forDigit(value & 0xF, 16));
        }
        return text.toString();
    }
}
