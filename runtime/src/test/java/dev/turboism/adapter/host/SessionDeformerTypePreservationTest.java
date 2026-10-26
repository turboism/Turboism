package dev.turboism.adapter.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport.Check;
import dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport.Operation;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;

class SessionDeformerTypePreservationTest {
    @Test
    void genericCollectionPreservesConcreteTypesAndForwardsTheirStoredContracts() {
        var f = new Fixture();
        var all = f.collection.all();
        assertInstanceOf(WarpDeformer.class, all.get(0));
        assertInstanceOf(RotationDeformer.class, all.get(1));
        assertFalse(all.get(2) instanceof WarpDeformer);
        assertFalse(all.get(2) instanceof RotationDeformer);
        assertEquals(
                List.of("warp", "rotation", "plain"),
                all.stream().map(d -> d.id().value()).toList());
        assertThrows(UnsupportedOperationException.class, () -> all.clear());
        var warp = assertInstanceOf(WarpDeformer.class, f.collection.find(new DeformerId("warp")));
        var rotation = assertInstanceOf(RotationDeformer.class, f.collection.find(new DeformerId("rotation")));
        assertSame(f.warpState, warp.authoringSnapshot());
        assertSame(f.rotationState, rotation.authoringSnapshot());
        assertEquals(Check.PRESENT, warp.ordinaryKeyformWriteSupport().selectorsFor(Operation.WARP_CONTROL_POINTS));
        assertEquals(Check.PRESENT, rotation.ordinaryKeyformWriteSupport().selectorsFor(Operation.ROTATION_FORMS));
        assertTrue(warp.ui().partPaletteEntry().isEmpty(), "Missing appearance provider remains unavailable");
        warp.setOrdinaryKeyformPositions(f.warpState, List.of());
        rotation.setOrdinaryKeyformForms(f.rotationState, List.of());
        assertEquals(List.of("warp.write", "rotation.write"), f.writes);
        f.collection.applyToChildren(warp);
        assertSame(f.warp, f.forwarded);
        f.collection.remove(rotation);
        assertSame(f.rotation, f.forwarded);
    }

    @Test
    void concreteHandlesAndCollectionCannotCrossSessionReplacementOrDisconnect() {
        for (boolean reconnect : List.of(false, true)) {
            var f = new Fixture();
            var warp = assertInstanceOf(WarpDeformer.class, f.collection.find(new DeformerId("warp")));
            var rotation = assertInstanceOf(RotationDeformer.class, f.collection.find(new DeformerId("rotation")));
            if (reconnect)
                f.host.connect(() -> {
                    throw new AssertionError("No new model lookup");
                });
            else f.host.deactivate();
            assertThrows(IllegalStateException.class, f.collection::all);
            assertThrows(IllegalStateException.class, () -> f.collection.find(new DeformerId("warp")));
            assertThrows(IllegalStateException.class, warp::authoringSnapshot);
            assertThrows(IllegalStateException.class, rotation::ordinaryKeyformWriteSupport);
            assertThrows(IllegalStateException.class, () -> warp.setOrdinaryKeyformPositions(f.warpState, List.of()));
            assertThrows(
                    IllegalStateException.class, () -> rotation.setOrdinaryKeyformForms(f.rotationState, List.of()));
            assertThrows(IllegalStateException.class, warp::ui);
            assertThrows(IllegalStateException.class, () -> f.collection.remove(warp));
            assertTrue(f.writes.isEmpty());
        }
    }

    private static final class Fixture {
        final DynamicCubismModelAccess host = new DynamicCubismModelAccess();
        final WarpAuthoringSnapshot warpState = new WarpAuthoringSnapshot(
                new DeformerId("warp"), "warp-source", 1, 1, false, List.of(), List.of(), List.of());
        final RotationAuthoringSnapshot rotationState = new RotationAuthoringSnapshot(
                new DeformerId("rotation"), "rotation-source", 0, List.of(), List.of(), List.of());
        final List<String> writes = new ArrayList<>();
        Deformer forwarded;
        final WarpDeformer warp = proxy(WarpDeformer.class, "warp");
        final RotationDeformer rotation = proxy(RotationDeformer.class, "rotation");
        final Deformer plain = proxy(Deformer.class, "plain");
        final SessionDeformers collection;

        Fixture() {
            host.connect(() -> {
                throw new AssertionError("No model lookup during retained calls");
            });
            var values = new Deformers() {
                public List<Deformer> all() {
                    return List.of(warp, rotation, plain);
                }

                public Deformer find(DeformerId id) {
                    return all().stream()
                            .filter(v -> v.id().equals(id))
                            .findFirst()
                            .orElseThrow();
                }

                public void remove(Deformer target) {
                    forwarded = target;
                }

                public void applyToChildren(Deformer target) {
                    forwarded = target;
                }
            };
            collection = new SessionDeformers(host, host.generation(), host.generation(), new ModelId("model"), values);
        }

        private <T extends Deformer> T proxy(Class<T> type, String id) {
            return type.cast(Proxy.newProxyInstance(
                    type.getClassLoader(), new Class<?>[] {type}, (o, method, args) -> switch (method.getName()) {
                        case "id" -> new DeformerId(id);
                        case "authoringSnapshot" -> id.equals("warp") ? warpState : rotationState;
                        case "ordinaryKeyformWriteSupport" ->
                            new OrdinaryKeyformWriteSupport(
                                    "5.3.04",
                                    Check.PRESENT,
                                    Check.PRESENT,
                                    Check.PRESENT,
                                    Check.PRESENT,
                                    Check.UNKNOWN,
                                    Map.of(
                                            id.equals("warp")
                                                    ? Operation.WARP_CONTROL_POINTS
                                                    : Operation.ROTATION_FORMS,
                                            Check.PRESENT));
                        case "setOrdinaryKeyformPositions", "setOrdinaryKeyformForms" -> {
                            assertSame(id.equals("warp") ? warpState : rotationState, args[0]);
                            assertEquals(List.of(), args[1]);
                            writes.add(id + ".write");
                            yield null;
                        }
                        default -> throw new AssertionError("Unexpected method: " + method.getName());
                    }));
        }
    }
}
