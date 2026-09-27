package dev.turboism.validation.modelupdate;

import java.lang.reflect.Method;

/** Read-only Windows/Proton own-process memory; no external process is opened. */
final class ProcessMemorySample {
    record Reading(long workingSet, long privateCommit) { }
    private final Object counters, function, process;
    private final Method invokeInt, readWord;
    private final int pointerBytes, size;

    ProcessMemorySample(ClassLoader hostLoader) throws ReflectiveOperationException {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            throw new UnsupportedOperationException("PSAPI only applies to Windows/Proton");
        }
        Class<?> nativeType = Class.forName("com.sun.jna.Native", true, hostLoader);
        pointerBytes = nativeType.getField("POINTER_SIZE").getInt(null);
        size = structureSize(pointerBytes);
        Class<?> memory = Class.forName("com.sun.jna.Memory", true, hostLoader);
        counters = memory.getConstructor(long.class).newInstance((long) size);
        memory.getMethod("setInt", long.class, int.class).invoke(counters, 0L, size);
        readWord = memory.getMethod(pointerBytes == 8 ? "getLong" : "getInt", long.class);
        Class<?> functions = Class.forName("com.sun.jna.Function", true, hostLoader);
        int convention = functions.getField("ALT_CONVENTION").getInt(null);
        Method get = functions.getMethod("getFunction", String.class, String.class, int.class);
        Object current = get.invoke(null, "kernel32", "GetCurrentProcess", convention);
        process = functions.getMethod("invokePointer", Object[].class)
            .invoke(current, (Object) new Object[0]);
        function = get.invoke(null, "psapi", "GetProcessMemoryInfo", convention);
        invokeInt = functions.getMethod("invokeInt", Object[].class);
    }
    Reading read() throws ReflectiveOperationException {
        int ok = ((Number) invokeInt.invoke(function, (Object) new Object[]{process, counters, size})).intValue();
        if (ok == 0) throw new IllegalStateException("GetProcessMemoryInfo returned false");
        return new Reading(word(8L + pointerBytes), word(8L + 8L * pointerBytes));
    }
    private long word(long offset) throws ReflectiveOperationException {
        Number value = (Number) readWord.invoke(counters, offset);
        return pointerBytes == 8 ? value.longValue() : Integer.toUnsignedLong(value.intValue());
    }
    static int structureSize(int pointerBytes) {
        if (pointerBytes != 4 && pointerBytes != 8) throw new IllegalArgumentException("unsupported pointer size");
        return 8 + 9 * pointerBytes;
    }
}
