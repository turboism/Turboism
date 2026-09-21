package dev.turboism.core.event;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Author-facing CLI that computes the values pinned in {@code plugin.json} for
 * public event contracts.
 *
 * <p>Usage:</p>
 *
 * <pre>
 *   # Digest SDK-owned event types resolved through the shared SDK loader:
 *   java -cp &lt;runtime classes&gt; dev.turboism.core.event.PublicEventAbiCli \
 *       dev.example.MyEvent
 *
 *   # Digest contract-owned event types and print the artifact digest:
 *   java -cp &lt;runtime classes&gt; dev.turboism.core.event.PublicEventAbiCli \
 *       --artifact acme-events-1.2.0.jar com.acme.events.SomethingHappened
 * </pre>
 *
 * <p>With {@code --artifact} the JAR is loaded through the same restricted
 * contract class loader the runtime binds at admission, so the printed
 * {@code abiSha256} is the exact value the session will recompute, and the
 * printed {@code artifactSha256} is the value to pin in
 * {@code eventContracts[].sha256}. Every named type must be a
 * {@code final record} implementing {@code EventBus.TurboismEvent} whose
 * payload closure stays inside the contract artifact, the JDK platform, and
 * {@code dev.turboism.sdk.*} — the same rules {@link PublicEventAbi} and
 * {@link PublicEventContractClosure} enforce at admission.</p>
 *
 * <p>Output lines are {@code <binaryName> <abiSha256>} followed, for
 * {@code --artifact} runs, by {@code artifactSha256 <digest>}. Exit status is
 * {@code 1} when arguments are malformed, a type fails to load, or a type
 * violates the contract rules.</p>
 */
public final class PublicEventAbiCli {

    private PublicEventAbiCli() {
    }

    /**
     * Prints the artifact SHA-256 and per-type ABI digests for a published contract JAR.
     *
     * @param args {@code --artifact <path>} followed by event type binary names
     */
    public static void main(final String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException | IOException failure) {
            System.err.println("public-event-abi: " + failure.getMessage());
            System.exit(1);
        }
    }

    private static void run(final String[] args) throws IOException {
        Path artifact = null;
        final List<String> typeNames = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            final String arg = args[index];
            if ("--artifact".equals(arg)) {
                if (++index >= args.length) {
                    throw new IllegalArgumentException("--artifact requires a JAR path");
                }
                artifact = Path.of(args[index]);
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("unknown option " + arg);
            } else {
                typeNames.add(arg);
            }
        }
        if (typeNames.isEmpty()) {
            throw new IllegalArgumentException(
                "usage: PublicEventAbiCli [--artifact contract.jar] <event binary name>..."
            );
        }
        if (artifact == null) {
            for (final String typeName : typeNames) {
                final Class<?> type = resolveSdk(typeName);
                System.out.println(typeName + " " + PublicEventAbi.sha256(type));
            }
            return;
        }
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalArgumentException("contract artifact not found: " + artifact);
        }
        final Set<String> owned = contractClassNames(artifact);
        try (ContractArtifactClassLoader loader = new ContractArtifactClassLoader(
            artifact.toUri().toURL(),
            owned
        )) {
            for (final String typeName : typeNames) {
                final Class<?> type;
                try {
                    type = Class.forName(typeName, false, loader);
                } catch (ClassNotFoundException failure) {
                    throw new IllegalArgumentException(
                        "event type is not owned by the contract artifact: " + typeName,
                        failure
                    );
                }
                if (type.getClassLoader() != loader) {
                    throw new IllegalArgumentException(
                        "event type was not defined by the contract artifact: " + typeName
                    );
                }
                requireEventRecord(typeName, type);
                PublicEventContractClosure.verify(type);
                System.out.println(typeName + " " + PublicEventAbi.sha256(type));
            }
        }
        System.out.println("artifactSha256 " + sha256Hex(Files.readAllBytes(artifact)));
    }

    private static Class<?> resolveSdk(final String typeName) {
        final Class<?> type;
        try {
            type = Class.forName(
                typeName,
                false,
                dev.turboism.sdk.event.EventBus.class.getClassLoader()
            );
        } catch (ClassNotFoundException failure) {
            throw new IllegalArgumentException(
                "event type is not visible to the shared SDK loader: " + typeName,
                failure
            );
        }
        requireEventRecord(typeName, type);
        return type;
    }

    private static void requireEventRecord(final String typeName, final Class<?> type) {
        if (!dev.turboism.sdk.event.EventBus.TurboismEvent.class.isAssignableFrom(type)
            || !type.isRecord()
            || !java.lang.reflect.Modifier.isFinal(type.getModifiers())) {
            throw new IllegalArgumentException(
                "public event payload type must be a final record implementing"
                    + " EventBus.TurboismEvent: " + typeName
            );
        }
    }

    private static Set<String> contractClassNames(final Path artifact) throws IOException {
        final Set<String> names = new LinkedHashSet<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final var stream = jar.stream();
            for (var iterator = stream.iterator(); iterator.hasNext(); ) {
                final JarEntry entry = iterator.next();
                final String name = entry.getName();
                if (!entry.isDirectory() && name.endsWith(".class")
                    && !name.equals("module-info.class")) {
                    names.add(name.substring(0, name.length() - ".class".length())
                        .replace('/', '.'));
                }
            }
        }
        return names;
    }

    private static String sha256Hex(final byte[] bytes) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
}
