package dev.turboism.adapter.cubism.profile;

import java.util.Arrays;
import java.util.Objects;

/**
 * One reviewed context-menu binding: the host method that opens the menu, the host method that
 * appends an item to it, and the cardinalities the transform proves before it rewrites anything.
 *
 * <p>The record carries no host class names of its own. A reviewed profile supplies them, so a
 * binding is a pure description that a profile can be pinned and compared by.</p>
 *
 * @param operationOwner internal name of the class whose method opens the menu
 * @param operationMethod exact name of the menu-opening method
 * @param operationDescriptor exact JVM descriptor of the menu-opening method
 * @param appendOwner internal name of the class whose method appends a menu item; {@code null} for a
 *     return point
 * @param appendMethod exact name of the append method; {@code null} for a return point
 * @param appendDescriptor exact JVM descriptor of the append method; {@code null} for a return point
 * @param location the menu location this binding serves
 * @param shape whether the binding injects at a return point or at an append point
 * @param expectedAppendPoints append points the reviewed body must declare; {@code 0} for a return
 *     point
 * @param injectionPoint the one-based append point the item is injected at; {@code 0} for a return
 *     point
 * @param sourceLocals local-variable slots the injected code reads, in order; empty for a return
 *     point
 */
public record ContextMenuBindingSpec(
        String operationOwner,
        String operationMethod,
        String operationDescriptor,
        String appendOwner,
        String appendMethod,
        String appendDescriptor,
        Location location,
        Shape shape,
        int expectedAppendPoints,
        int injectionPoint,
        int[] sourceLocals) {

    /** The two injection shapes a reviewed context-menu binding declares. */
    public enum Shape {
        /** Injects at the single return of the menu-opening method. */
        RETURN_POINT,
        /** Injects at one of the append calls inside the menu-opening method. */
        APPEND_POINT
    }

    /** The menu locations a binding may serve, ordered as the reviewed profiles declare them. */
    public enum Location {
        /** Deformer tab context menu. */
        DEFORMER_TAB,
        /** Parameter tab context menu. */
        PARAMETER_TAB,
        /** Parts tab context menu. */
        PART_TAB,
        /** Workspace object context menu. */
        WORKSPACE_OBJECT
    }

    public ContextMenuBindingSpec {
        operationOwner = require(operationOwner, "operationOwner");
        operationMethod = require(operationMethod, "operationMethod");
        operationDescriptor = require(operationDescriptor, "operationDescriptor");
        location = Objects.requireNonNull(location, "location");
        shape = Objects.requireNonNull(shape, "shape");
        sourceLocals = sourceLocals.clone();
        if (shape == Shape.APPEND_POINT) {
            appendOwner = require(appendOwner, "appendOwner");
            appendMethod = require(appendMethod, "appendMethod");
            appendDescriptor = require(appendDescriptor, "appendDescriptor");
        } else if (appendOwner != null || appendMethod != null || appendDescriptor != null) {
            throw new IllegalArgumentException("return-point binding must not declare an append selector");
        }
        if (shape == Shape.APPEND_POINT
                && (expectedAppendPoints <= 0 || injectionPoint <= 0 || injectionPoint > expectedAppendPoints)) {
            throw new IllegalArgumentException("append cardinality and injection point must be positive and bounded");
        }
        if (shape == Shape.APPEND_POINT
                && (sourceLocals.length == 0 || Arrays.stream(sourceLocals).anyMatch(value -> value < 0))) {
            throw new IllegalArgumentException("append-point source locals must be non-empty and non-negative");
        }
        if (shape == Shape.RETURN_POINT
                && (expectedAppendPoints != 0 || injectionPoint != 0 || sourceLocals.length != 0)) {
            throw new IllegalArgumentException("return-point binding must not declare append cardinality");
        }
    }

    /**
     * A binding that injects at the single return of the menu-opening method.
     *
     * @param operationOwner internal name of the class whose method opens the menu
     * @param operationMethod exact name of the menu-opening method
     * @param operationDescriptor exact JVM descriptor of the menu-opening method
     * @param location the menu location this binding serves
     * @return the return-point binding
     */
    public static ContextMenuBindingSpec returnPoint(
            final String operationOwner,
            final String operationMethod,
            final String operationDescriptor,
            final Location location) {
        return new ContextMenuBindingSpec(
                operationOwner,
                operationMethod,
                operationDescriptor,
                null,
                null,
                null,
                location,
                Shape.RETURN_POINT,
                0,
                0,
                new int[0]);
    }

    /**
     * A binding that injects at one append call inside the menu-opening method.
     *
     * @param operationOwner internal name of the class whose method opens the menu
     * @param operationMethod exact name of the menu-opening method
     * @param operationDescriptor exact JVM descriptor of the menu-opening method
     * @param appendOwner internal name of the class whose method appends a menu item
     * @param appendMethod exact name of the append method
     * @param appendDescriptor exact JVM descriptor of the append method
     * @param location the menu location this binding serves
     * @param expectedAppendPoints append points the reviewed body must declare
     * @param injectionPoint the one-based append point the item is injected at
     * @param sourceLocals local-variable slots the injected code reads, in order
     * @return the append-point binding
     */
    public static ContextMenuBindingSpec appendPoint(
            final String operationOwner,
            final String operationMethod,
            final String operationDescriptor,
            final String appendOwner,
            final String appendMethod,
            final String appendDescriptor,
            final Location location,
            final int expectedAppendPoints,
            final int injectionPoint,
            final int... sourceLocals) {
        return new ContextMenuBindingSpec(
                operationOwner,
                operationMethod,
                operationDescriptor,
                appendOwner,
                appendMethod,
                appendDescriptor,
                location,
                Shape.APPEND_POINT,
                expectedAppendPoints,
                injectionPoint,
                sourceLocals);
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) return true;
        if (!(other instanceof ContextMenuBindingSpec spec)) return false;
        return expectedAppendPoints == spec.expectedAppendPoints
                && injectionPoint == spec.injectionPoint
                && location == spec.location
                && shape == spec.shape
                && operationOwner.equals(spec.operationOwner)
                && operationMethod.equals(spec.operationMethod)
                && operationDescriptor.equals(spec.operationDescriptor)
                && Objects.equals(appendOwner, spec.appendOwner)
                && Objects.equals(appendMethod, spec.appendMethod)
                && Objects.equals(appendDescriptor, spec.appendDescriptor)
                && Arrays.equals(sourceLocals, spec.sourceLocals);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                operationOwner,
                operationMethod,
                operationDescriptor,
                appendOwner,
                appendMethod,
                appendDescriptor,
                location,
                shape,
                expectedAppendPoints,
                injectionPoint,
                Arrays.hashCode(sourceLocals));
    }

    @Override
    public String toString() {
        return "ContextMenuBindingSpec[" + operationOwner + '.' + operationMethod + operationDescriptor + " -> "
                + location + '/' + shape + ", appendPoints=" + expectedAppendPoints + ", injectionPoint="
                + injectionPoint + ", sourceLocals=" + Arrays.toString(sourceLocals) + ']';
    }

    private static String require(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
