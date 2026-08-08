package dev.turboism.adapter.cubism.filechooser;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.JFileChooser;

/**
 * Reflection adapter over Cubism file-chooser history internals, ported from
 * the reviewed legacy behavior (fields {@code b} = history list, {@code d} =
 * chooser implementation with {@code setCurrentDirectory}/{@code getSelectedFile}).
 * All access is fail-closed: callers wrap invocations in try/catch.
 */
public final class FileChooserHistoryHostAdapter {

    private FileChooserHistoryHostAdapter() {
    }

    /**
     * Applies the given history to a raw chooser: writes the normalized list
     * into field {@code b} and sets the current directory to the first entry.
     */
    public static void applyHistory(final Object chooser, final List<File> history) {
        if (chooser == null || history == null || history.isEmpty()) {
            return;
        }
        final List<File> normalized = new ArrayList<>();
        addHistoryEntries(normalized, history);
        if (normalized.isEmpty()) {
            return;
        }
        writeFieldDeep(chooser, "b", new ArrayList<>(normalized));
        final Object chooserImpl = readFieldDeep(chooser, "d");
        invoke(chooserImpl, "setCurrentDirectory", new Class<?>[]{File.class}, normalized.get(0));
    }

    /**
     * Captures the remembered directories from a raw chooser: reads field
     * {@code b}; falls back to the implementation's {@code getSelectedFile}
     * (or {@link JFileChooser#getSelectedFile()} when the chooser is one).
     */
    public static List<File> captureHistory(final Object chooser) {
        if (chooser == null) {
            return Collections.emptyList();
        }
        final List<File> result = new ArrayList<>();
        final Object rawHistory = readFieldDeep(chooser, "b");
        if (rawHistory instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                final File dir = normalizeExistingDirectory(toFile(item));
                if (dir == null || containsFile(result, dir)) {
                    continue;
                }
                result.add(dir);
            }
        }
        if (!result.isEmpty()) {
            return result;
        }
        final Object chooserImpl = readFieldDeep(chooser, "d");
        final File dir = normalizeExistingDirectory(toFile(invoke(chooserImpl, "getSelectedFile")));
        if (dir == null && chooser instanceof JFileChooser jfileChooser) {
            return Collections.singletonList(normalizeExistingDirectory(jfileChooser.getSelectedFile()));
        }
        return dir == null ? result : List.of(dir);
    }

    private static void addHistoryEntries(final List<File> target, final List<File> source) {
        for (File item : source) {
            final File dir = normalizeExistingDirectory(item);
            if (dir == null || containsFile(target, dir)) {
                continue;
            }
            target.add(dir);
        }
    }

    private static File toFile(final Object value) {
        if (value instanceof File file) {
            return file;
        }
        final String text = normalize(value == null ? null : String.valueOf(value));
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : new File(text);
    }

    private static File normalizeExistingDirectory(final File file) {
        if (file == null) {
            return null;
        }
        final File dir = file.isDirectory() ? file : file.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        return dir;
    }

    private static boolean containsFile(final List<File> files, final File candidate) {
        final String target = normalize(candidate.getAbsolutePath());
        for (File file : files) {
            if (file != null && target.equals(normalize(file.getAbsolutePath()))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(final String text) {
        return text == null ? "" : text.trim();
    }

    private static Object readFieldDeep(final Object target, final String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // keep walking the hierarchy
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                    "cannot read field " + name + " on " + target.getClass().getName(), failure
                );
            }
        }
        throw new IllegalStateException("field " + name + " not found on " + target.getClass().getName());
    }

    private static void writeFieldDeep(final Object target, final String name, final Object value) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // keep walking the hierarchy
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                    "cannot write field " + name + " on " + target.getClass().getName(), failure
                );
            }
        }
        throw new IllegalStateException("field " + name + " not found on " + target.getClass().getName());
    }

    private static Object invoke(final Object target, final String name, final Class<?>[] types, final Object argument) {
        if (target == null) {
            return null;
        }
        try {
            final Method method = target.getClass().getMethod(name, types);
            return method.invoke(target, argument);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                "cannot invoke " + name + " on " + target.getClass().getName(), failure
            );
        }
    }

    private static Object invoke(final Object target, final String name) {
        if (target == null) {
            return null;
        }
        try {
            final Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                "cannot invoke " + name + " on " + target.getClass().getName(), failure
            );
        }
    }
}
