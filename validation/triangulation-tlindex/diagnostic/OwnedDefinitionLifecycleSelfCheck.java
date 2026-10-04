package dev.turboism.validation.tlindex.diagnostic;

import dev.turboism.adapter.cubism.mesh.TriangulationDefinitionLifecycle;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.*;

/** Executes only owned classes in a real JVM; does not load official Cubism code. */
public final class OwnedDefinitionLifecycleSelfCheck implements Opcodes {
    private static int checks;
    private static final String NAME = "dev/turboism/validation/tlindex/diagnostic/owned/OwnedLifecycleValue";
    private OwnedDefinitionLifecycleSelfCheck() {}
    private static void require(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message + " check=" + checks);
    }
    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, NAME, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()I", null, null);
        method.visitCode(); method.visitIntInsn(BIPUSH, 3); method.visitInsn(IRETURN);
        method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static Class<?> define() {
        class Loader extends ClassLoader {
            Loader() { super(OwnedDefinitionLifecycleSelfCheck.class.getClassLoader()); }
            Class<?> define() { byte[] bytes = fixture(); return defineClass(NAME.replace('/', '.'), bytes, 0, bytes.length); }
        }
        return new Loader().define();
    }
    private static int value(Class<?> type) throws Exception { return (int) type.getMethod("value").invoke(null); }
    private static Map<String, String> reviewed() { return Map.of(NAME.replace('/', '.'), DefinitionFingerprint.runtimeOf(fixture())); }
    private static TriangulationDefinitionLifecycle.Gate capture(TriangulationDefinitionLifecycle owner, Class<?> type) {
        return owner.capture(new Class<?>[] {type}, reviewed(), DefinitionFingerprint::runtimeOf);
    }
    private static boolean admitted(TriangulationDefinitionLifecycle.Gate gate) {
        try (var lease = gate.acquire()) { return lease != null; }
    }
    private static ClassFileTransformer modifier(Class<?> target, AtomicBoolean active) {
        return new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                if (type != target || !active.get()) return null;
                ClassReader reader = new ClassReader(bytes); ClassWriter writer = new ClassWriter(reader, 0);
                reader.accept(new ClassVisitor(ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!name.equals("value")) return output;
                        return new MethodVisitor(ASM9, output) {
                            @Override public void visitIntInsn(int opcode, int operand) { super.visitIntInsn(opcode, operand == 3 ? 9 : operand); }
                        };
                    }
                }, 0); return writer.toByteArray();
            }
        };
    }
    private static Thread worker(String name, Runnable action, AtomicReference<Throwable> failure) {
        return new Thread(() -> { try { action.run(); } catch (Throwable thrown) { failure.compareAndSet(null, thrown); } }, name);
    }
    private static void joined(Thread thread, AtomicReference<Throwable> failure) throws Exception {
        thread.join(5000); require(!thread.isAlive(), "worker did not complete: " + thread.getName());
        require(failure.get() == null, "worker failed: " + failure.get());
    }

    private static void registrationDuringCapture(TriangulationDefinitionLifecycle owner) throws Exception {
        Instrumentation instrumentation = owner.instrumentation(); Class<?> type = define();
        ClassFileTransformer late = modifier(type, new AtomicBoolean(true));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch attempting = new CountDownLatch(1), completed = new CountDownLatch(1);
        Thread contender = worker("owned-late-registration", () -> {
            attempting.countDown(); instrumentation.addTransformer(late, true); completed.countDown();
        }, failure);
        try {
            var gate = owner.capture(new Class<?>[] {type}, reviewed(), bytes -> {
                contender.start();
                try {
                    require(attempting.await(5, TimeUnit.SECONDS), "registration contender started");
                    require(!completed.await(150, TimeUnit.MILLISECONDS), "registration passed exclusive capture");
                } catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                return DefinitionFingerprint.runtimeOf(bytes);
            });
            joined(contender, failure);
            require(value(type) == 3, "captured JVM definition changed downstream");
            require(!admitted(gate), "late registration did not retire captured gate");
            instrumentation.retransformClasses(type);
            require(value(type) == 9 && !admitted(gate), "changed JVM code remained admitted");
        } finally { instrumentation.removeTransformer(late); }
    }

    private static void operationLease(TriangulationDefinitionLifecycle owner) throws Exception {
        Instrumentation instrumentation = owner.instrumentation(); Class<?> type = define(); AtomicBoolean active = new AtomicBoolean();
        ClassFileTransformer modifier = modifier(type, active); instrumentation.addTransformer(modifier, true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch attempting = new CountDownLatch(1), completed = new CountDownLatch(1);
        Thread mutator = worker("owned-retransform-waits", () -> {
            attempting.countDown();
            try { instrumentation.retransformClasses(type); }
            catch (Exception error) { throw new IllegalStateException(error); }
            completed.countDown();
        }, failure);
        try {
            var gate = capture(owner, type); var lease = gate.acquire(); require(lease != null, "reviewed lease denied: " + gate.reason());
            try (lease) {
                active.set(true); mutator.start(); require(attempting.await(5, TimeUnit.SECONDS), "mutator started");
                require(!completed.await(150, TimeUnit.MILLISECONDS), "retransform passed live operation lease");
                require(value(type) == 3, "operation definition changed before lease release");
                boolean rejected = false;
                try { instrumentation.retransformClasses(type); } catch (IllegalStateException expected) { rejected = true; }
                require(rejected, "same-thread read/write upgrade did not refuse");
                require(!admitted(owner.capture(new Class<?>[] {type}, reviewed(), DefinitionFingerprint::runtimeOf)), "capture within lease did not refuse");
                Thread wrongCloser = worker("owned-wrong-lease-owner", () -> {
                    try { lease.close(); throw new AssertionError("wrong-thread close accepted"); }
                    catch (IllegalStateException expected) { /* Original thread retains its lease. */ }
                }, failure);
                wrongCloser.start(); joined(wrongCloser, failure);
                require(value(type) == 3 && completed.getCount() == 1, "wrong-thread close released protection");
            }
            lease.close(); joined(mutator, failure);
            require(value(type) == 9 && !admitted(gate), "post-lease mutation did not retire gate");
            require(!admitted(capture(owner, type)), "changed live definition admitted");
            active.set(false); instrumentation.retransformClasses(type);
            require(value(type) == 3 && !admitted(gate), "old gate revived after definition restoration");
            var restored = capture(owner, type); require(admitted(restored), "explicit restored capture failed");
            // Capture itself retransforms: even a failed later capture must retire prior gates.
            active.set(true); require(!admitted(capture(owner, type)), "capture missed modifier");
            require(value(type) == 9 && !admitted(restored), "failed capture left earlier gate admitted");
        } finally { instrumentation.removeTransformer(modifier); }
    }

    private static void callbacksCannotWait(TriangulationDefinitionLifecycle owner) throws Exception {
        Instrumentation instrumentation = owner.instrumentation(); Class<?> type = define();
        AtomicBoolean registrationRejected = new AtomicBoolean(), recursiveRejected = new AtomicBoolean(), leaseRefused = new AtomicBoolean();
        AtomicReference<TriangulationDefinitionLifecycle.Gate> previous = new AtomicReference<>();
        ClassFileTransformer forbidden = modifier(type, new AtomicBoolean(true));
        ClassFileTransformer callback = new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> target, ProtectionDomain domain, byte[] bytes) {
                if (target == null && NAME.equals(name) && previous.get() != null) {
                    leaseRefused.set(!admitted(previous.get()));
                    return null;
                }
                if (target != type) return null;
                try { instrumentation.addTransformer(forbidden, true); }
                catch (IllegalStateException expected) { registrationRejected.set(true); }
                try { instrumentation.retransformClasses(type); }
                catch (IllegalStateException expected) { recursiveRejected.set(true); }
                catch (Exception unexpected) { throw new IllegalStateException(unexpected); }
                var gate = previous.get(); if (gate != null) leaseRefused.set(!admitted(gate));
                return null;
            }
        };
        instrumentation.addTransformer(callback, true);
        try {
            var gate = capture(owner, type); previous.set(gate);
            require(admitted(gate) && value(type) == 3, "same-callback downstream mutation escaped");
            require(registrationRejected.get() && recursiveRejected.get(), "callback mutation did not refuse before JVM entry");
            define();
            require(leaseRefused.get() && admitted(gate), "initial-definition callback acquired a live operation lease");
            instrumentation.retransformClasses(type);
            require(leaseRefused.get(), "callback waited for operation admission");
            require(value(type) == 3 && !admitted(gate), "callback mutated approved definition");
        } finally { instrumentation.removeTransformer(callback); }
    }

    private static class InheritedCallbackBase {
        final Instrumentation instrumentation;
        final Class<?> target;
        final ClassFileTransformer late;
        final AtomicBoolean rejected;
        InheritedCallbackBase(Instrumentation instrumentation, Class<?> target, ClassFileTransformer late, AtomicBoolean rejected) {
            this.instrumentation = instrumentation; this.target = target; this.late = late; this.rejected = rejected;
        }
        // The declaring class intentionally does not implement ClassFileTransformer.
        public byte[] transform(Module module, ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
            if (type == target) {
                try { instrumentation.addTransformer(late, true); }
                catch (IllegalStateException expected) { rejected.set(true); }
            }
            return null;
        }
    }

    private static final class InheritedCallback extends InheritedCallbackBase implements ClassFileTransformer {
        InheritedCallback(Instrumentation instrumentation, Class<?> target, ClassFileTransformer late, AtomicBoolean rejected) {
            super(instrumentation, target, late, rejected);
        }
    }

    private static void inheritedCallbackCannotMutate(TriangulationDefinitionLifecycle owner) throws Exception {
        Instrumentation instrumentation = owner.instrumentation(); Class<?> type = define(); AtomicBoolean rejected = new AtomicBoolean();
        ClassFileTransformer late = modifier(type, new AtomicBoolean(true));
        ClassFileTransformer inherited = new InheritedCallback(instrumentation, type, late, rejected);
        instrumentation.addTransformer(inherited, true);
        try {
            var gate = capture(owner, type);
            require(rejected.get(), "inherited module-aware callback bypassed mutation refusal");
            require(admitted(gate) && value(type) == 3, "inherited callback altered live definition");
        } finally {
            instrumentation.removeTransformer(late); instrumentation.removeTransformer(inherited);
        }
    }

    private record Released(WeakReference<ClassLoader> loader, TriangulationDefinitionLifecycle.Gate gate) {}

    private static void callbackRegistrationIdentity(TriangulationDefinitionLifecycle owner) throws Exception {
        Instrumentation instrumentation = owner.instrumentation(); AtomicInteger invoked = new AtomicInteger();
        int expectedRetransforms = duplicateRetransformCount(DefinitionAdmissionSelfCheckAgent.instrumentation());
        ClassFileTransformer duplicate = new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                if (NAME.equals(name)) invoked.incrementAndGet();
                return null;
            }
        };
        instrumentation.addTransformer(duplicate, false); instrumentation.addTransformer(duplicate, true);
        try {
            Class<?> probe = define(); require(invoked.getAndSet(0) == 2, "duplicate registrations were collapsed");
            require(instrumentation.removeTransformer(duplicate), "most recent duplicate not removed");
            instrumentation.retransformClasses(probe);
            require(invoked.getAndSet(0) == expectedRetransforms,
                    "duplicate removal changed actual raw capability-group selection");
            define(); require(invoked.getAndSet(0) == 1, "duplicate removal removed both registrations");
            require(instrumentation.removeTransformer(duplicate), "remaining duplicate not removed");
            define(); require(invoked.getAndSet(0) == 0, "removed callback retained in JVM");
            require(!instrumentation.removeTransformer(duplicate), "unknown transformer falsely removed");
        } finally { while (instrumentation.removeTransformer(duplicate)) { } }
    }

    private static int duplicateRetransformCount(Instrumentation raw) throws Exception {
        AtomicInteger count = new AtomicInteger();
        ClassFileTransformer duplicate = new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                if (NAME.equals(name)) count.incrementAndGet();
                return null;
            }
        };
        Class<?> probe = define();raw.addTransformer(duplicate, false);raw.addTransformer(duplicate, true);
        try {
            require(raw.removeTransformer(duplicate), "raw duplicate removal");raw.retransformClasses(probe);return count.get();
        } finally { while (raw.removeTransformer(duplicate)) { } }
    }

    private static void throwingCallbackRestoresContext(TriangulationDefinitionLifecycle owner) {
        Instrumentation instrumentation = owner.instrumentation(); AtomicBoolean refused = new AtomicBoolean();
        AtomicBoolean nestedRefused = new AtomicBoolean();
        AtomicReference<TriangulationDefinitionLifecycle.Gate> previous = new AtomicReference<>();
        ClassFileTransformer callback = new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                if (type == null && NAME.equals(name) && previous.get() != null) {
                    refused.set(!admitted(previous.get()));
                    define();
                    nestedRefused.set(!admitted(previous.get()));
                    throw new IllegalStateException("owned callback failure control");
                }
                return null;
            }
        };
        instrumentation.addTransformer(callback, true);
        try {
            var gate = capture(owner, define()); previous.set(gate);
            define(); require(refused.get(), "throwing initial callback acquired lease");
            require(nestedRefused.get(), "nested class load cleared callback context");
            require(admitted(gate), "callback exception retained context outside JVM callback");
        } finally { instrumentation.removeTransformer(callback); }
    }
    private static Released releaseFixture(TriangulationDefinitionLifecycle owner) {
        Class<?> type = define(); var gate = capture(owner, type); require(admitted(gate), "release fixture denied");
        return new Released(new WeakReference<>(type.getClassLoader()), gate);
    }

    public static void main(String[] arguments) throws Exception {
        Instrumentation raw = DefinitionAdmissionSelfCheckAgent.instrumentation();
        require(raw != null, "owned premain provider absent");
        try (var owner = TriangulationDefinitionLifecycle.forPremain(raw, DefinitionAdmissionSelfCheckAgent.class.getName())) {
            System.out.println("startupReason=" + owner.startupReason());
            boolean boot = arguments.length == 1 && arguments[0].equals("boot");
            if (boot) require(TriangulationDefinitionLifecycle.class.getClassLoader() == null, "helper was not boot-loaded");
            if (arguments.length == 1 && !boot) {
                require(owner.startupReason().equals(arguments[0]), "startup rejection reason");
                require(owner.instrumentation() == raw, "unsupported startup changed existing instrumentation behavior");
                require(TriangulationDefinitionLifecycle.ownedBy(raw) == null, "raw handle exposed verified ownership");
                require(!admitted(capture(owner, define())), "unsupported startup admitted operation");
            } else {
                require(owner.startupReason().equals("SUPPORTED_OWNED_PREMAIN"), "owned startup not admitted");
                Instrumentation instrumentation = owner.instrumentation();
                require(instrumentation != raw && instrumentation.equals(instrumentation), "owned handle identity");
                require(TriangulationDefinitionLifecycle.ownedBy(instrumentation) == owner, "installer ownership handoff missing");
                Class<?> first = define(); var good = capture(owner, first);
                require(admitted(good) && value(first) == 3, "reviewed actual definition not admitted");
                require(!admitted(owner.capture(new Class<?>[] {first, first}, reviewed(), DefinitionFingerprint::runtimeOf)), "duplicate/incomplete dependencies admitted");
                require(!admitted(owner.capture(new Class<?>[] {first}, reviewed(), null)), "missing fingerprinter admitted");
                var cloned = owner.capture(new Class<?>[] {first}, reviewed(), bytes -> {
                    String hash = DefinitionFingerprint.runtimeOf(bytes); java.util.Arrays.fill(bytes, (byte) 0); return hash;
                });
                require(admitted(cloned) && value(first) == 3, "fingerprinter changed JVM bytes");
                require(!admitted(owner.capture(new Class<?>[] {first}, reviewed(), bytes -> { throw new IllegalArgumentException("owned rejection"); })), "fingerprint failure admitted");
                require(!admitted(cloned), "failed capture did not retire old gate");
                registrationDuringCapture(owner); operationLease(owner); callbacksCannotWait(owner); inheritedCallbackCannotMutate(owner);
                callbackRegistrationIdentity(owner); throwingCallbackRestoresContext(owner);
                Released released = releaseFixture(owner);
                for (int attempt = 0; attempt < 80 && released.loader.get() != null; attempt++) { System.gc(); Thread.sleep(10); }
                require(released.loader.get() == null && admitted(released.gate), "retained gate kept application loader alive");
                try (var wrongManifest = TriangulationDefinitionLifecycle.forPremain(raw, "wrong.Premain")) {
                    require(wrongManifest.startupReason().equals("AGENT_MANIFEST_REJECTED") && !admitted(capture(wrongManifest, define())), "wrong premain admitted");
                }
            }
        }
        // A fresh owner must also reject capture after close; no application definitions are rooted.
        var closed = TriangulationDefinitionLifecycle.forPremain(raw, DefinitionAdmissionSelfCheckAgent.class.getName());
        closed.close(); closed.close(); require(!admitted(capture(closed, define())), "closed scope admitted");
        System.out.println("checks=" + checks + " PASS; officialCodeExecuted=false; bootstrapInstalled=false");
    }
}
