package dev.turboism.validation.texture;

import java.nio.file.Files;

/** Offline: transformed catch handlers retain return values and exception identity. */
public final class HostAdmissionDiagnosticTest {
    public static class Fixture {
        public static Object caught(RuntimeException e) {
            try { throw e; } catch (RuntimeException same) { return same; }
        }
        public static int normal() { return 17; }
    }
    public static void main(String[] args) throws Exception {
        var output = Files.createTempFile("atlas-admission-test", ".txt");
        var field = HostAdmissionDiagnostic.class.getDeclaredField("output");
        field.setAccessible(true); field.set(null, output);
        System.getProperties().put(HostAdmissionDiagnostic.KEY,
            (java.util.function.Consumer<Throwable>) HostAdmissionDiagnostic::record);
        try {
            byte[] original;
            try (var input = Fixture.class.getResourceAsStream("HostAdmissionDiagnosticTest$Fixture.class")) {
                original = input.readAllBytes();
            }
            byte[] bytes = HostAdmissionDiagnostic.observe(original);
            Class<?> transformed = new ClassLoader(HostAdmissionDiagnosticTest.class.getClassLoader()) {
                Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.equals(HostAdmissionDiagnostic.class.getName())) throw new ClassNotFoundException("Probe intentionally invisible");
                    return super.loadClass(name, resolve);
                }
            }.define();
            var sentinel = new IllegalStateException("offline-sentinel");
            if (transformed.getMethod("caught", RuntimeException.class).invoke(null, sentinel) != sentinel)
                throw new AssertionError("Exception identity changed");
            if (!transformed.getMethod("normal").invoke(null).equals(17)) throw new AssertionError("Return changed");
            if (!Files.readString(output).contains("offline-sentinel")) throw new AssertionError("Missing observation");
            // Verify the actual production HostSession bytecode without constructing a host session.
            ClassLoader productionCopy = new ClassLoader(HostAdmissionDiagnosticTest.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (!name.startsWith("dev.turboism.adapter.host.")) return super.loadClass(name, resolve);
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) {
                            try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                                if (input == null) throw new ClassNotFoundException(name);
                                byte[] bytes = input.readAllBytes();
                                if (name.equals("dev.turboism.adapter.host.HostSession")) bytes = HostAdmissionDiagnostic.observe(bytes);
                                type = defineClass(name, bytes, 0, bytes.length);
                            } catch (java.io.IOException e) { throw new ClassNotFoundException(name, e); }
                        }
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }
            };
            Class<?> real = productionCopy.loadClass("dev.turboism.adapter.host.HostSession");
            real.getDeclaredMethods();
            System.out.println("PASS: exception identity, normal return, recorded cause, production bytecode verification");
        } finally { System.getProperties().remove(HostAdmissionDiagnostic.KEY); field.set(null, null); Files.deleteIfExists(output); }
    }
}
