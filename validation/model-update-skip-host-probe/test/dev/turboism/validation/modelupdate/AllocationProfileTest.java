package dev.turboism.validation.modelupdate;

import java.util.List;

/** Pure aggregation regression; not an allocation-accuracy or host test. */
public final class AllocationProfileTest {
    public static void main(String[] args) {
        AllocationProfile profile = new AllocationProfile();
        profile.add("AWT-EventQueue-0", "[F", 128, List.of("com.live2d.graphics3d.type.GMatrix44.<init>", "com.live2d.graphics3d.component.GTransform.getLocalToWorldMatrix"));
        profile.add("AWT-EventQueue-0", "[F", 256, List.of("com.live2d.graphics3d.type.GMatrix44.<init>", "com.live2d.graphics3d.component.GTransform.getLocalToWorldMatrix"));
        profile.add("AWT-EventQueue-0", "java.util.HashMap$Node", 64, List.of("java.util.HashMap.putVal", "dev.turboism.adapter.cubism.optimization.uniform.FrameUniformLocationCache.record"));
        profile.add("GC Thread", "[B", 4096, List.of("other.allocate"));
        String report = profile.report();
        check(report.contains("allocation.edtSamples=3\n"), "thread scope");
        check(report.contains("allocation.edtSampleWeight=448\n"), "weights include all EDT samples");
        check(report.contains("allocation.firstEdtSampleWeight=128\n"), "first sample is disclosed, never silently removed");
        check(report.contains("allocation.category.native.matrix.weight=384\n"), "matrix categorization");
        check(report.contains("allocation.category.turboism.uniform.weight=64\n"), "own overhead separately reported");
        check(report.contains("allocation.exactBytes=false\n"), "weight is not exact allocated bytes");
        check(report.contains("GTransform.getLocalToWorldMatrix"), "allocation stack retained");
        try { profile.add("AWT-EventQueue-0", "[F", -1, List.of()); throw new AssertionError("negative weight accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("AllocationProfileTest PASS (thread, sampled weights, first sample, categories, stacks)");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
