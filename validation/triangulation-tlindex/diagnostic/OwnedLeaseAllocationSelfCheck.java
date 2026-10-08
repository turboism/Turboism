package dev.turboism.validation.tlindex.diagnostic;

import com.sun.management.ThreadMXBean;
import dev.turboism.adapter.cubism.mesh.TriangulationDefinitionLifecycle;
import java.lang.management.ManagementFactory;

/** Owned admission-path allocation accounting; no Cubism or production gain claim. */
public final class OwnedLeaseAllocationSelfCheck {
    private OwnedLeaseAllocationSelfCheck() {}

    public static void main(String[] arguments) throws Exception {
        try (var owner = TriangulationDefinitionLifecycle.forPremain(
                DefinitionAdmissionSelfCheckAgent.instrumentation(), DefinitionAdmissionSelfCheckAgent.class.getName())) {
            if (!owner.startupReason().equals("SUPPORTED_OWNED_PREMAIN")) throw new AssertionError("unsupported owner");
            var define = OwnedDefinitionLifecycleSelfCheck.class.getDeclaredMethod("define");
            var capture = OwnedDefinitionLifecycleSelfCheck.class.getDeclaredMethod(
                    "capture", TriangulationDefinitionLifecycle.class, Class.class);
            define.setAccessible(true); capture.setAccessible(true);
            var gate = (TriangulationDefinitionLifecycle.Gate) capture.invoke(null, owner, define.invoke(null));
            for (int i = 0; i < 3000; i++) {
                try (var lease = gate.acquire()) { if (lease == null) throw new AssertionError("warmup refused"); }
            }
            ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            if (!bean.isThreadAllocatedMemorySupported() || !bean.isThreadAllocatedMemoryEnabled())
                throw new AssertionError("allocation counter unavailable");
            long thread = Thread.currentThread().getId();
            long start = bean.getThreadAllocatedBytes(thread);
            int operations = 10000;
            for (int i = 0; i < operations; i++) {
                try (var lease = gate.acquire()) { if (lease == null) throw new AssertionError("admission refused"); }
            }
            long bytes = bean.getThreadAllocatedBytes(thread) - start;
            if (bytes < 0) throw new AssertionError("invalid allocation counter");
            System.out.println("OWNED_LEASE_ALLOCATION_PASS operations=" + operations + " allocatedBytes=" + bytes
                    + " editorLaunched=false performanceAcceptance=NOT_GRANTED");
        }
    }
}
