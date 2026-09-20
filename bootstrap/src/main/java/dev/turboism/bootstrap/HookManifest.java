package dev.turboism.bootstrap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads the declarative install-time hook manifest from
 * {@code META-INF/turboism/hooks}.
 *
 * <p>The manifest is a plain-text seed list: one contributor class name per
 * line, {@code #} comments and blank lines allowed. Loading is fail-closed —
 * a missing manifest, a malformed line, a duplicate entry, an unloadable
 * class or a class that does not implement {@link HookContributor} rejects the
 * whole manifest and no hook is installed.</p>
 */
final class HookManifest {

    static final String RESOURCE = "META-INF/turboism/hooks";

    private HookManifest() {
    }

    /**
     * Loads and instantiates every declared contributor.
     *
     * @param loader the class loader that owns the agent classes
     * @return the contributors in manifest order; never {@code null}
     * @throws HookManifestException when the manifest is missing or invalid
     */
    static List<HookContributor> load(final ClassLoader loader) throws HookManifestException {
        final List<String> classNames = readClassNames(loader);
        final List<HookContributor> contributors = new ArrayList<>(classNames.size());
        for (String className : classNames) {
            contributors.add(instantiate(loader, className));
        }
        return List.copyOf(contributors);
    }

    private static List<String> readClassNames(final ClassLoader loader) throws HookManifestException {
        final Set<String> classNames = new LinkedHashSet<>();
        try (InputStream stream = loader.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new HookManifestException("hook manifest is missing: " + RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
            )) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    final String entry = line.trim();
                    if (entry.isEmpty() || entry.startsWith("#")) {
                        continue;
                    }
                    if (!entry.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) {
                        throw new HookManifestException(
                            "hook manifest line " + lineNumber + " is not a class name: " + entry
                        );
                    }
                    if (!classNames.add(entry)) {
                        throw new HookManifestException(
                            "hook manifest declares a duplicate contributor: " + entry
                        );
                    }
                }
            }
        } catch (IOException failure) {
            throw new HookManifestException("hook manifest could not be read", failure);
        }
        if (classNames.isEmpty()) {
            throw new HookManifestException("hook manifest declares no contributors");
        }
        return List.copyOf(classNames);
    }

    private static HookContributor instantiate(
        final ClassLoader loader,
        final String className
    ) throws HookManifestException {
        final Class<?> type;
        try {
            type = Class.forName(className, false, loader);
        } catch (Throwable failure) {
            throw new HookManifestException("hook contributor class is unavailable: " + className, failure);
        }
        if (!HookContributor.class.isAssignableFrom(type)) {
            throw new HookManifestException(
                "hook contributor does not implement HookContributor: " + className
            );
        }
        final Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor();
        } catch (NoSuchMethodException failure) {
            throw new HookManifestException(
                "hook contributor lacks a no-arg constructor: " + className, failure
            );
        }
        try {
            constructor.setAccessible(true);
            return (HookContributor) constructor.newInstance();
        } catch (Throwable failure) {
            throw new HookManifestException(
                "hook contributor could not be instantiated: " + className, failure
            );
        }
    }

    static final class HookManifestException extends Exception {
        HookManifestException(final String message) {
            super(message);
        }

        HookManifestException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
