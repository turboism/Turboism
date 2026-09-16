package dev.turboism.validation.modelupdate;

import java.util.List;

/** Pure snapshot regression; no native Editor classes or resources are loaded. */
public final class NativeInteractionHostTest {
    public static void main(String[] args) {
        float[] mesh = {0, 0, 1, 0, 0, 1};
        float[] moved = {2, 1, 3, 1, 2, 2};
        float[] point = {2, 1, 1, 0, 0, 1};
        var baseline = geometry(mesh, mesh, mesh, mesh);
        var keyformOnly = geometry(mesh, mesh, mesh, moved);
        check(NativeInteractionHost.changed(baseline, keyformOnly).equals(List.of(0)), "keyform edits must not disappear from source-only checks");
        check(NativeInteractionHost.wholeMeshChanged(baseline, keyformOnly, 0), "all keyform vertices moved");
        check(!NativeInteractionHost.wholeMeshChanged(baseline, geometry(mesh, mesh, mesh, point), 0), "single point is not an ArtMesh translation");
        var calculatedOnly = geometry(mesh, mesh, moved, mesh);
        check(NativeInteractionHost.changed(baseline, calculatedOnly).equals(List.of(0)), "evaluated canvas geometry must be checked");
        check(NativeInteractionHost.changed(baseline, geometry(mesh.clone(), mesh.clone(), mesh.clone(), mesh.clone())).isEmpty(), "restoration compares content rather than array identity");
        System.out.println("NativeInteractionHostTest PASS (keyforms, source, evaluation, whole-mesh verification)");
    }
    private static NativeInteractionHost.Geometry geometry(float[] interpolated, float[] source, float[] calculated, float[] keyform) {
        return new NativeInteractionHost.Geometry(List.of(interpolated), List.of(source), List.of(calculated), List.of(List.of(keyform)));
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
