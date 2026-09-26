package dev.turboism.validation.externalpsd;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Failure-only metadata from this JVM's handles. Never closes an observed handle. */
final class WindowsPsdHandleObservation {
    private static final String JNA_SHA =
        "5557e235a8aa2f9766d5dc609d67948f2a8832c2d796cea9ef1d6cbe0b3b7eaf";
    private static final int TABLE_LIMIT = 4 * 1024 * 1024;
    private WindowsPsdHandleObservation() { }

    static void observe(Properties result, String prefix, ClassLoader loader,
        Path hostArtifact, Path target, Path sibling) {
        result.setProperty(prefix + "status", "UNAVAILABLE");
        result.setProperty(prefix + "semantics",
            "current JVM disk handles only; racing metadata, not Windows sharing-mode proof");
        try {
            if (!System.getProperty("os.name", "").startsWith("Windows"))
                throw new IllegalStateException("Windows only");
            final Class<?> nativeType = checked(loader, hostArtifact, "com.sun.jna.Native");
            final Class<?> function = checked(loader, hostArtifact, "com.sun.jna.Function");
            final Class<?> pointer = checked(loader, hostArtifact, "com.sun.jna.Pointer");
            if (nativeType.getField("POINTER_SIZE").getInt(null) != 8)
                throw new IllegalStateException("64-bit layout required");
            final Method get = function.getMethod("getFunction", String.class, String.class);
            final Method invoke = function.getMethod("invokeInt", Object[].class);
            final Object query = get.invoke(null, "ntdll", "NtQuerySystemInformation");
            final Object getPid = get.invoke(null, "kernel32", "GetCurrentProcessId");
            final Object type = get.invoke(null, "kernel32", "GetFileType");
            final Object name = get.invoke(null, "kernel32", "GetFinalPathNameByHandleW");
            final long pid = Integer.toUnsignedLong(call(invoke, getPid));
            result.setProperty(prefix + "processId", Long.toString(pid));
            final long deadline = System.nanoTime() + 2_000_000_000L;
            final ByteBuffer table = ByteBuffer.allocateDirect(TABLE_LIMIT).order(ByteOrder.LITTLE_ENDIAN);
            final int[] returned = new int[1];
            final int status = call(invoke, query, 64, table, TABLE_LIMIT, returned);
            result.setProperty(prefix + "ntStatus", Integer.toUnsignedString(status, 16));
            if (status != 0) throw new IllegalStateException("handle table query unsupported or too large");
            final List<Long> handles = currentHandles(table, returned[0], pid);
            int seen = 0, matches = 0, failed = 0;
            boolean partial = false;
            for (long handle : handles) {
                if (seen >= 4096 || System.nanoTime() >= deadline) { partial = true; break; }
                seen++;
                final Object h = pointer.getMethod("createConstant", long.class).invoke(null, handle);
                final int fileType = call(invoke, type, h);
                if (fileType == 0) { failed++; continue; }
                if (fileType != 1) continue; // FILE_TYPE_DISK only; never query pipes.
                final char[] buffer = new char[32768];
                final int length = call(invoke, name, h, buffer, buffer.length, 0);
                if (length <= 0 || length >= buffer.length) { failed++; continue; }
                final String path = new String(buffer, 0, length);
                final String matched = sameWindowsPath(path, target.toString()) ? "target"
                    : sameWindowsPath(path, sibling.toString()) ? "temporary" : "";
                if (!matched.isEmpty()) {
                    result.setProperty(prefix + "match." + matches + ".handle", Long.toUnsignedString(handle));
                    result.setProperty(prefix + "match." + matches + ".file", matched);
                    matches++;
                }
            }
            result.setProperty(prefix + "examined", Integer.toString(seen));
            result.setProperty(prefix + "matches", Integer.toString(matches));
            result.setProperty(prefix + "failedQueries", Integer.toString(failed));
            result.setProperty(prefix + "status", partial || failed > 0 ? "PARTIAL" : "OBSERVED");
        } catch (Exception | LinkageError | OutOfMemoryError failure) {
            result.setProperty(prefix + "diagnostic", failure.getClass().getSimpleName()
                + ": " + String.valueOf(failure.getMessage()));
        }
    }

    private static int call(Method invoke, Object function, Object... args) throws Exception {
        return (Integer) invoke.invoke(function, (Object) args);
    }

    private static Class<?> checked(ClassLoader loader, Path host, String name) throws Exception {
        final Class<?> type = Class.forName(name, false, loader);
        final Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (type.getClassLoader() != loader || !actual.equals(host.resolveSibling("jna-5.6.0.jar"))
            || !JNA_SHA.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(actual)))))
            throw new IllegalStateException("unverified JNA artifact/loader");
        return type;
    }

    static List<Long> currentHandles(ByteBuffer table, int returned, long pid) {
        // SYSTEM_HANDLE_INFORMATION_EX: two pointer-sized fields, then 40-byte entries on x64.
        if (returned < 16 || returned > table.capacity()) throw new IllegalArgumentException("invalid table length");
        final ByteBuffer data = table.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final long count = data.getLong(0);
        if (count < 0 || count > (returned - 16L) / 40)
            throw new IllegalArgumentException("invalid handle count");
        final List<Long> handles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int offset = 16 + i * 40;
            if (data.getLong(offset + 8) == pid) handles.add(data.getLong(offset + 16));
        }
        return List.copyOf(handles);
    }

    static boolean sameWindowsPath(String observed, String expected) {
        return normalized(observed).equals(normalized(expected));
    }

    private static String normalized(String path) {
        String value = path.replace('/', '\\');
        if (value.startsWith("\\\\?\\UNC\\")) value = "\\\\" + value.substring(8);
        else if (value.startsWith("\\\\?\\")) value = value.substring(4);
        return value.toLowerCase(Locale.ROOT);
    }
}
