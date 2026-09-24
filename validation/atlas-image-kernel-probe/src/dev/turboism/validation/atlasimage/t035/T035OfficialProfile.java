package dev.turboism.validation.atlasimage.t035;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Fixed, read-only identities and complete shape gates for the three official inputs. */
final class T035OfficialProfile {
    static final String DESCRIPTOR = "(II[III[IIIII)V";
    static final int CLASS_VERSION = 61;
    static final int CLASS_ACCESS = 49;
    static final int METHOD_ACCESS = 17;
    static final int METHOD_COUNT = 14;
    static final int MAX_STACK = 5;
    static final int MAX_LOCALS = 35;
    static final int INSTRUCTION_COUNT = 244;
    static final int RETURN_OFFSET = 447;
    static final int FILL_OFFSET = 68;
    static final int HEIGHT_BOUNDARY_OFFSET = 74;
    static final int WIDTH_BOUNDARY_OFFSET = 121;
    static final int SHAPE_LINE_COUNT = 260;

    private static final java.nio.file.Path PROTON_PREFIX =
        java.nio.file.Path.of(System.getProperty("user.home"), ".proton", "pfx");

    private static final List<Profile> PROFILES = List.of(
        new Profile(
            "5203",
            PROTON_PREFIX.resolve("drive_c/Program Files/Live2D Cubism 5.2/app/lib/Live2D_Cubism.jar"),
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
            "com/live2d/util/e/g.class",
            "com/live2d/util/e/g",
            "9df616c60ade56657214465b2b18f18df0605faeeac28d99663ce959af11615b",
            "cc4a05429efdca26a6308162a3fbb67fd4fd503c7e4260ed51fd4568f6b1facb"
        ),
        new Profile(
            "5302",
            PROTON_PREFIX.resolve("drive_c/Program Files/Live2D Cubism 5.3/app/lib/Live2D_Cubism.jar"),
            "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
            "com/live2d/util/f/g.class",
            "com/live2d/util/f/g",
            "ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6",
            "a75d64a1203e3e80be09bc616b7878d52d31e6a1327a62cbbbf1b5a334432c2f"
        ),
        new Profile(
            "5303",
            PROTON_PREFIX.resolve("drive_c/Program Files/Live2D Cubism 5.3.03/app/lib/Live2D_Cubism.jar"),
            "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166",
            "com/live2d/util/f/g.class",
            "com/live2d/util/f/g",
            "ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6",
            "a75d64a1203e3e80be09bc616b7878d52d31e6a1327a62cbbbf1b5a334432c2f"
        )
    );

    private T035OfficialProfile() {
    }

    static List<Loaded> loadAll() throws IOException {
        final List<Loaded> loaded = new java.util.ArrayList<>();
        for (final Profile profile : PROFILES) {
            if (!Files.isRegularFile(profile.jarPath())) {
                throw new IOException("official JAR missing: " + profile.jarPath());
            }
            final String actualJarSha = sha256File(profile.jarPath());
            check(actualJarSha.equals(profile.jarSha256()),
                profile.label() + " official JAR hash mismatch: " + actualJarSha);
            final byte[] classBytes;
            try (JarFile jar = new JarFile(profile.jarPath().toFile())) {
                final JarEntry entry = jar.getJarEntry(profile.entryName());
                if (entry == null) {
                    throw new IOException(profile.label() + " official entry missing: " + profile.entryName());
                }
                try (InputStream stream = jar.getInputStream(entry)) {
                    classBytes = stream.readAllBytes();
                }
            }
            final String actualClassSha = T035Shape.sha256(classBytes);
            check(actualClassSha.equals(profile.classSha256()),
                profile.label() + " official class hash mismatch: " + actualClassSha);
            final T035Shape.Shape shape = T035Shape.inspect(classBytes);
            checkProfile(profile, shape);
            loaded.add(new Loaded(profile, classBytes, shape));
        }
        return List.copyOf(loaded);
    }

    static void print(final Loaded loaded) {
        final Profile profile = loaded.profile();
        System.out.println("t035OfficialData version=" + profile.label()
            + " jarSha256=" + profile.jarSha256()
            + " classSha256=" + profile.classSha256()
            + " shapeSha256=" + loaded.shape().sha256()
            + " resolutionQueries=0");
    }

    static void checkProfile(final Profile profile, final T035Shape.Shape shape) {
        check(shape.version() == CLASS_VERSION, profile.label() + " class version gate failed");
        check(shape.classAccess() == CLASS_ACCESS, profile.label() + " class access gate failed");
        check(shape.className().equals(profile.owner()), profile.label() + " owner gate failed");
        check(shape.methodCount() == METHOD_COUNT, profile.label() + " method-count gate failed");
        check(shape.methodAccess() == METHOD_ACCESS, profile.label() + " B flags gate failed");
        check(shape.methodName().equals("a") && shape.descriptor().equals(DESCRIPTOR),
            profile.label() + " B identity gate failed");
        check(shape.maxStack() == MAX_STACK && shape.maxLocals() == MAX_LOCALS,
            profile.label() + " max stack/locals gate failed");
        check(shape.instructionCount() == INSTRUCTION_COUNT,
            profile.label() + " instruction-count gate failed");
        check(shape.handlerCount() == 0, profile.label() + " exception-table gate failed");
        check(shape.lines().size() == SHAPE_LINE_COUNT, profile.label() + " shape-line count gate failed");
        check(shape.sha256().equals(profile.shapeSha256()), profile.label() + " complete CFG/frame hash gate failed");
        check(shape.hasLine("68: M 184 java/util/Arrays.fill([IIII)V false"),
            profile.label() + " fill offset gate failed");
        check(shape.hasLine("74: V 21 9") && shape.hasLine("121: V 21 8"),
            profile.label() + " boundary predecessor gate failed");
        check(shape.hasLine("447: I 177") && shape.hasLine("MAX 5 35"),
            profile.label() + " return/max gate failed");
        check(shape.hasLine("321: J 167 202")
                && shape.hasLine("334: J 167 184")
                && shape.hasLine("431: J 167 134")
                && shape.hasLine("444: J 167 87"),
            profile.label() + " four direct loop-back CFG gate failed");
        check(shape.hasLine("87: FRAME [0, 1, 1, [I, 1, 1, [I, 1, 1, 0, 1, 1, 1, 1] STACK []"),
            profile.label() + " frame/TOP gate failed at first loop body");
    }

    private static String sha256File(final Path path) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(path)) {
                final byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            return hex(digest.digest());
        } catch (final NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    private static String hex(final byte[] value) {
        final StringBuilder result = new StringBuilder(value.length * 2);
        for (final byte next : value) {
            result.append(String.format("%02x", next & 0xff));
        }
        return result.toString();
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    record Profile(
        String label,
        Path jarPath,
        String jarSha256,
        String entryName,
        String owner,
        String classSha256,
        String shapeSha256
    ) {
    }

    record Loaded(Profile profile, byte[] classBytes, T035Shape.Shape shape) {
        Loaded {
            classBytes = classBytes.clone();
        }
    }
}
