package dev.turboism.adapter.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport.Check;
import dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport.Operation;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SessionOrdinaryKeyformWriteSupportTest {
    @Test
    void allFourSessionViewsForwardExactObservationWithoutProbingAnyOtherMethod() {
        var host = new DynamicCubismModelAccess();
        host.connect(() -> {
            throw new AssertionError("No active model lookup needed for a retained view");
        });
        var calls = new AtomicInteger();
        var expected = new OrdinaryKeyformWriteSupport(
                "5.3.04",
                Check.PRESENT,
                Check.PRESENT,
                Check.PRESENT,
                Check.PRESENT,
                Check.UNKNOWN,
                Map.of(Operation.ART_MESH_VALUES_WITH_OPACITY, Check.ABSENT));
        var queries = queries(host, calls, expected);
        for (var query : queries) assertSame(expected, query.get());
        assertEquals(4, calls.get());
    }

    @Test
    void disconnectAndReplacementRejectRetainedQueriesBeforeTouchingDelegate() {
        for (boolean reconnect : List.of(false, true)) {
            var host = new DynamicCubismModelAccess();
            host.connect(() -> {
                throw new AssertionError("Unexpected model access");
            });
            var calls = new AtomicInteger();
            var queries = queries(host, calls, OrdinaryKeyformWriteSupport.unknown());
            if (reconnect)
                host.connect(() -> {
                    throw new AssertionError("Unexpected replacement access");
                });
            else host.deactivate();
            for (var query : queries) assertThrows(IllegalStateException.class, query::get);
            assertEquals(0, calls.get());
        }
    }

    private static List<Supplier<OrdinaryKeyformWriteSupport>> queries(
            DynamicCubismModelAccess host, AtomicInteger calls, OrdinaryKeyformWriteSupport result) {
        long generation = host.generation();
        var id = new ModelId("model");
        var mesh = new SessionDrawable(host, generation, generation, id, delegate(Drawable.class, calls, result));
        var deformer = new SessionDeformer(host, generation, generation, id, delegate(Deformer.class, calls, result));
        var warp = new SessionWarpDeformer(host, generation, delegate(WarpDeformer.class, calls, result));
        var rotation = new SessionRotationDeformer(host, generation, delegate(RotationDeformer.class, calls, result));
        return List.of(
                mesh::ordinaryKeyformWriteSupport,
                deformer::ordinaryKeyformWriteSupport,
                warp::ordinaryKeyformWriteSupport,
                rotation::ordinaryKeyformWriteSupport);
    }

    private static <T> T delegate(Class<T> type, AtomicInteger calls, OrdinaryKeyformWriteSupport result) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (o, method, args) -> {
            if (!method.getName().equals("ordinaryKeyformWriteSupport"))
                throw new AssertionError("Unexpected method: " + method.getName());
            calls.incrementAndGet();
            return result;
        }));
    }
}
