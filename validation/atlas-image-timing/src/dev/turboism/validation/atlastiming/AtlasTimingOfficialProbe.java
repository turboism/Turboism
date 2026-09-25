package dev.turboism.validation.atlastiming;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Read-only verification that every timing target exists exactly once in the official JAR.
 *
 * <p>The official class entries are only read and hashed; they are never defined, verified,
 * executed, written to disk, or placed on a classpath.</p>
 */
public final class AtlasTimingOfficialProbe {
    private AtlasTimingOfficialProbe() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalStateException(
                "usage: AtlasTimingOfficialProbe <Live2D_Cubism.jar> [5303|5203]");
        }
        final List<AtlasTimingTargets.Target> targets = args.length == 2
            ? switch (args[1]) {
                case "5303" -> AtlasTimingTargets.cubism5303();
                case "5203" -> AtlasTimingTargets.cubism5203();
                default -> throw new IllegalStateException("profile must be 5303 or 5203");
            }
            : AtlasTimingTargets.cubism5303();
        final Map<String, byte[]> owners = new LinkedHashMap<>();
        try (ZipFile archive = new ZipFile(Path.of(args[0]).toFile())) {
            for (final AtlasTimingTargets.Target target : targets) {
                owners.computeIfAbsent(target.ownerInternalName(), name -> {
                    try {
                        final ZipEntry entry = archive.getEntry(name + ".class");
                        if (entry == null) return null;
                        try (InputStream stream = archive.getInputStream(entry)) {
                            return stream.readAllBytes();
                        }
                    } catch (Exception failure) {
                        return null;
                    }
                });
            }
        }

        int failures = 0;
        final Map<String, Integer> found = new LinkedHashMap<>();
        for (final Map.Entry<String, byte[]> owner : owners.entrySet()) {
            if (owner.getValue() == null) {
                System.out.println("OFFICIAL_OWNER ABSENT " + owner.getKey());
                failures++;
                continue;
            }
            System.out.println("OFFICIAL_OWNER " + owner.getKey()
                + " size=" + owner.getValue().length
                + " sha256=" + sha256(owner.getValue()));
            new ClassReader(owner.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(final int access, final String name,
                                                 final String descriptor, final String signature,
                                                 final String[] exceptions) {
                    for (final AtlasTimingTargets.Target target : targets) {
                        if (!target.ownerInternalName().equals(owner.getKey())) continue;
                        if (target.methodName().equals(name)
                            && target.descriptor().equals(descriptor)) {
                            found.merge(owner.getKey() + "." + name + descriptor, 1, Integer::sum);
                        }
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        for (final AtlasTimingTargets.Target target : targets) {
            final String key = target.ownerInternalName() + "." + target.methodName()
                + target.descriptor();
            final int count = found.getOrDefault(key, 0);
            System.out.println("OFFICIAL_TARGET " + key + " matched=" + count);
            if (count != 1) failures++;
        }
        if (failures > 0) {
            System.out.println("ATLAS_TIMING_OFFICIAL_PROBE BLOCKED failures=" + failures
                + " officialDefined=false");
            System.exit(1);
        }
        System.out.println("ATLAS_TIMING_OFFICIAL_PROBE PASS"
            + " officialDefined=false officialExecuted=false officialWritten=false");
    }

    private static String sha256(final byte[] bytes) throws Exception {
        final byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        final StringBuilder text = new StringBuilder(hash.length * 2);
        for (final byte value : hash) {
            text.append(Character.forDigit((value >> 4) & 0xF, 16));
            text.append(Character.forDigit(value & 0xF, 16));
        }
        return text.toString();
    }
}
