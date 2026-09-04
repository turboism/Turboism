package dev.turboism.validation.boundingbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.BiFunction;

/**
 * Deterministic self-check for the loader-neutral setup-bridge telemetry
 * wrapper: identity-safe wrap, single delegation with unchanged arguments and
 * an unchanged callback result, result-classification counters, unchanged
 * rethrow of delegated failures, identity-safe restore that never clobbers
 * or removes another owner's value, and structured not-observed outcome
 * markers. Runs on a private {@link Properties}
 * instance with no sleeps and no timing thresholds. Lives in the plugin package
 * so the package-private {@link BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry}
 * stays test-only internal; it compiles into the self-check output, never the
 * probe JAR.
 */
public final class BridgeTelemetrySelfCheck {

    private static final String KEY = BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry.SETUP_PROPERTY;

    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(final String[] args) {
        wrapDelegatesOnceWithUnchangedResult();
        classifiesDelegatedResults();
        rethrowsDelegatedFailuresUnchanged();
        restoresOriginalIdentitySafely();
        neverClobbersAnotherOwnersValue();
        outcomeMarkersAreStructured();
        if (FAILURES.isEmpty()) {
            System.out.println("BOUNDING_BOX_BRIDGE_TELEMETRY_SELFCHECK status=PASS checks=6");
            return;
        }
        for (String failure : FAILURES) {
            System.err.println("BOUNDING_BOX_BRIDGE_TELEMETRY_SELFCHECK failure: " + failure);
        }
        System.err.println("BOUNDING_BOX_BRIDGE_TELEMETRY_SELFCHECK status=FAIL checks=" + FAILURES.size());
        Runtime.getRuntime().exit(1);
    }

    private static void wrapDelegatesOnceWithUnchangedResult() {
        final Properties properties = new Properties();
        final Object[] delegated = { "a", "b" };
        final Object[] seenArgs = new Object[2];
        final BiFunction<Object, Object, Object> original = (overlay, scene) -> {
            seenArgs[0] = overlay;
            seenArgs[1] = scene;
            return delegated;
        };
        properties.put(KEY, original);
        final BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry telemetry =
            new BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry();
        final BiFunction<Object, Object, Object> wrapper = telemetry.installWrapper(properties, original);
        check("wrapper installed", wrapper != null && telemetry.wrapperInstalled(), String.valueOf(wrapper));
        check("property now maps to the wrapper", properties.get(KEY) == wrapper, String.valueOf(properties.get(KEY)));
        final Object overlay = new Object();
        final Object scene = new Object();
        final Object result = wrapper.apply(overlay, scene);
        check("delegated exactly once", telemetry.invocations() == 1, "invocations=" + telemetry.invocations());
        check("arguments unchanged", seenArgs[0] == overlay && seenArgs[1] == scene,
            "seen=" + seenArgs[0] + "," + seenArgs[1]);
        check("callback result unchanged by identity", result == delegated, String.valueOf(result));
    }

    private static void classifiesDelegatedResults() {
        final Properties properties = new Properties();
        final BiFunction<Object, Object, Object> original = (overlay, scene) -> switch (String.valueOf(scene)) {
            case "null" -> null;
            case "non-array" -> "not-an-array";
            case "empty" -> new Object[0];
            default -> new Object[] { 1, 2, 3 };
        };
        properties.put(KEY, original);
        final BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry telemetry =
            new BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry();
        final BiFunction<Object, Object, Object> wrapper = telemetry.installWrapper(properties, original);
        check("wrapper installed", wrapper != null && telemetry.wrapperInstalled(), String.valueOf(wrapper));
        wrapper.apply("x", "null");
        wrapper.apply("x", "non-array");
        wrapper.apply("x", "empty");
        wrapper.apply("x", "full");
        check("invocations counted", telemetry.invocations() == 4, "invocations=" + telemetry.invocations());
        check("null results classified", telemetry.nullResults() == 1, "nullResults=" + telemetry.nullResults());
        check("non-array results classified", telemetry.nonArrayResults() == 1,
            "nonArrayResults=" + telemetry.nonArrayResults());
        check("empty arrays classified", telemetry.emptyArrays() == 1, "emptyArrays=" + telemetry.emptyArrays());
        check("non-empty arrays classified",
            telemetry.nonEmptyArrays() == 1 && telemetry.maxArrayLength() == 3,
            "nonEmptyArrays=" + telemetry.nonEmptyArrays() + " max=" + telemetry.maxArrayLength());
    }

    private static void rethrowsDelegatedFailuresUnchanged() {
        final Properties properties = new Properties();
        final IllegalStateException failure = new IllegalStateException("delegated failure");
        final BiFunction<Object, Object, Object> original = (a, b) -> {
            throw failure;
        };
        properties.put(KEY, original);
        final BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry telemetry =
            new BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry();
        final BiFunction<Object, Object, Object> wrapper = telemetry.installWrapper(properties, original);
        check("wrapper installed", wrapper != null && telemetry.wrapperInstalled(), String.valueOf(wrapper));
        Object thrown = null;
        try {
            wrapper.apply("x", "y");
        } catch (RuntimeException caught) {
            thrown = caught;
        }
        check("delegated failure rethrown unchanged", thrown == failure, "thrown=" + thrown);
        check("throwables counted", telemetry.throwables() == 1, "throwables=" + telemetry.throwables());
    }

    private static void restoresOriginalIdentitySafely() {
        final Properties properties = new Properties();
        final BiFunction<Object, Object, Object> original = (a, b) -> new Object[0];
        properties.put(KEY, original);
        final BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry telemetry =
            new BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry();
        final BiFunction<Object, Object, Object> wrapper = telemetry.installWrapper(properties, original);
        check("wrapper installed", wrapper != null, String.valueOf(wrapper));
        check("restore succeeded", telemetry.restoreWrapper(properties) && telemetry.wrapperRestored(),
            "restored=" + telemetry.wrapperRestored());
        check("original restored by identity", properties.get(KEY) == original, String.valueOf(properties.get(KEY)));
        check("callback never removed", properties.containsKey(KEY), "missing");
        check("second restore is a no-op", !telemetry.restoreWrapper(properties), "second restore changed the value");
    }

    private static void neverClobbersAnotherOwnersValue() {
        final Properties properties = new Properties();
        final BiFunction<Object, Object, Object> original = (a, b) -> new Object[0];
        final Object replacedByOther = new Object();
        properties.put(KEY, replacedByOther);
        final BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry telemetry =
            new BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry();
        final BiFunction<Object, Object, Object> wrapper = telemetry.installWrapper(properties, original);
        check("wrap refused when the observed original is gone",
            wrapper == null && !telemetry.wrapperInstalled(),
            "wrapper=" + wrapper + " installed=" + telemetry.wrapperInstalled());
        check("other owner's value untouched", properties.get(KEY) == replacedByOther,
            String.valueOf(properties.get(KEY)));
        check("nothing removed", properties.containsKey(KEY), "missing");
    }

    private static void outcomeMarkersAreStructured() {
        check("timeout marker structured",
            "BOUNDING_BOX_BRIDGE_NOT_OBSERVED reason=timeout bridgeObserved=false".equals(
                BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry.notObservedMarker("timeout")),
            BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry.notObservedMarker("timeout"));
        check("stopped marker structured",
            "BOUNDING_BOX_BRIDGE_NOT_OBSERVED reason=stopped bridgeObserved=false".equals(
                BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry.notObservedMarker("stopped")),
            BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry.notObservedMarker("stopped"));
        check("interrupted marker structured",
            "BOUNDING_BOX_BRIDGE_NOT_OBSERVED reason=interrupted bridgeObserved=false".equals(
                BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry.notObservedMarker("interrupted")),
            BoundingBoxOverlayHostValidationPlugin.BridgeTelemetry.notObservedMarker("interrupted"));
    }

    private static void check(final String name, final boolean condition, final String detail) {
        if (!condition) {
            FAILURES.add(name + ": " + detail);
        }
    }
}
