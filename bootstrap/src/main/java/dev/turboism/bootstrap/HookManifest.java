package dev.turboism.bootstrap;

import dev.turboism.core.runtime.work.FatalErrors;
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
import java.util.regex.Pattern;

/**
 * Loads the declarative install-time hook manifest from {@code META-INF/turboism/hooks}.
 *
 * <p>The manifest is a plain-text seed list with one entry per line. An entry is either a
 * fully-qualified {@link HookContributor} class name or a {@code @} token that names a
 * {@link DeclarativeHook} entry in {@link DeclarativeHooks}. {@code #} comments and blank lines are
 * allowed. Coexistence is deliberate: an unmigrated family keeps its contributor class, and a
 * migrated family declares one token instead of a class per install.</p>
 *
 * <p>Loading is fail-closed. A missing manifest, a malformed line, a duplicate entry, an unknown
 * declarative token, an unloadable class, a class that does not implement {@link HookContributor}, or
 * two entries that report under the same hook identity reject the whole manifest, and no hook is
 * installed. A duplicate identity check matters because two contributors with one report id would
 * silently overwrite each other's installation outcome.</p>
 */
final class HookManifest {

    static final String RESOURCE = "META-INF/turboism/hooks";

    /** Prefix that marks a manifest line as a {@link DeclarativeHooks} token instead of a class name. */
    static final String DECLARATIVE_PREFIX = "@";

    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

    private static final Pattern DECLARATIVE_TOKEN = Pattern.compile("[a-z][a-z0-9-]*");

    private HookManifest() {}

    /**
     * Loads and instantiates every declared contributor, in manifest order.
     *
     * @param loader the class loader that owns the agent classes, or {@code null} when the agent runs
     *     on the bootstrap class path
     * @return the contributors in manifest order; never {@code null}
     * @throws HookManifestException when the manifest is missing or invalid
     */
    static List<HookContributor> load(final ClassLoader loader) throws HookManifestException {
        final List<Entry> entries = readEntries(loader);
        final List<HookContributor> contributors = new ArrayList<>(entries.size());
        for (final Entry entry : entries) {
            contributors.add(
                    entry instanceof Entry.ClassEntry classEntry
                            ? instantiate(loader, classEntry.className())
                            : DeclarativeHookContributor.of(((Entry.DeclarativeEntry) entry).hook()));
        }
        requireDistinctIdentities(contributors);
        return List.copyOf(contributors);
    }

    /**
     * Rejects a manifest whose entries report under one hook identity: two contributors with one
     * report id would silently overwrite each other's installation outcome.
     *
     * @param contributors the contributors in manifest order
     * @throws HookManifestException when two contributors share a report id
     */
    static void requireDistinctIdentities(final List<HookContributor> contributors) throws HookManifestException {
        final Set<String> identities = new LinkedHashSet<>();
        for (final HookContributor contributor : contributors) {
            if (!identities.add(contributor.id())) {
                throw new HookManifestException(
                        "hook manifest declares a duplicate hook identity: " + contributor.id());
            }
        }
    }

    // The distributed agent declares Boot-Class-Path, so its classes (and this
    // manifest reader) have no defining loader. The -javaagent jar is also on
    // the system class path, which carries the same manifest resource.
    private static InputStream openManifest(final ClassLoader loader) {
        if (loader != null) {
            return loader.getResourceAsStream(RESOURCE);
        }
        return ClassLoader.getSystemResourceAsStream(RESOURCE);
    }

    private static List<Entry> readEntries(final ClassLoader loader) throws HookManifestException {
        final Set<String> declared = new LinkedHashSet<>();
        final List<Entry> entries = new ArrayList<>();
        try (InputStream stream = openManifest(loader)) {
            if (stream == null) {
                throw new HookManifestException("hook manifest is missing: " + RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    final String entry = line.trim();
                    if (entry.isEmpty() || entry.startsWith("#")) {
                        continue;
                    }
                    if (!declared.add(entry)) {
                        throw new HookManifestException("hook manifest declares a duplicate entry: " + entry);
                    }
                    entries.add(parse(lineNumber, entry));
                }
            }
        } catch (IOException failure) {
            throw new HookManifestException("hook manifest could not be read", failure);
        }
        if (entries.isEmpty()) {
            throw new HookManifestException("hook manifest declares no contributors");
        }
        return List.copyOf(entries);
    }

    private static Entry parse(final int lineNumber, final String entry) throws HookManifestException {
        if (!entry.startsWith(DECLARATIVE_PREFIX)) {
            if (!CLASS_NAME.matcher(entry).matches()) {
                throw new HookManifestException("hook manifest line " + lineNumber + " is not a class name: " + entry);
            }
            return new Entry.ClassEntry(entry);
        }
        final String token = entry.substring(DECLARATIVE_PREFIX.length());
        if (!DECLARATIVE_TOKEN.matcher(token).matches()) {
            throw new HookManifestException(
                    "hook manifest line " + lineNumber + " is not a declarative hook token: " + entry);
        }
        return DeclarativeHooks.byKey(token)
                .<Entry>map(Entry.DeclarativeEntry::new)
                .orElseThrow(() -> new HookManifestException(
                        "hook manifest line " + lineNumber + " names an unknown declarative hook: " + entry));
    }

    private static HookContributor instantiate(final ClassLoader loader, final String className)
            throws HookManifestException {
        final Class<?> type;
        try {
            type = Class.forName(className, false, loader);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new HookManifestException("hook contributor class is unavailable: " + className, failure);
        }
        if (!HookContributor.class.isAssignableFrom(type)) {
            throw new HookManifestException("hook contributor does not implement HookContributor: " + className);
        }
        final Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor();
        } catch (NoSuchMethodException failure) {
            throw new HookManifestException("hook contributor lacks a no-arg constructor: " + className, failure);
        }
        try {
            constructor.setAccessible(true);
            return (HookContributor) constructor.newInstance();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new HookManifestException("hook contributor could not be instantiated: " + className, failure);
        }
    }

    /** One parsed manifest line: a contributor class or a declarative registry token. */
    private sealed interface Entry {

        /** @param className the fully-qualified contributor class the line names */
        record ClassEntry(String className) implements Entry {}

        /** @param hook the registry entry the line names */
        record DeclarativeEntry(DeclarativeHook hook) implements Entry {}
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
