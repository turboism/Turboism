package dev.turboism.automesh.host;

import java.util.List;

/**
 * Executable stand-in for a host Auto Mesh Generate container.
 *
 * <p>The reviewed Editor cannot start headless, so the attach control flow is proven on this class:
 * it carries the same constructor shape as the native containers — one {@code (Object)V}
 * constructor, one return, no branch — it is loaded from its own bytes through a child-first loader,
 * and its counters show whether the attach callback ran and how often the body executed.</p>
 */
public final class ContainerFixture {

    /** Number of times the constructor body executed. */
    public int constructorCalls;

    /** Last pack argument the constructor observed. */
    public Object lastPack;

    /**
     * Constructor body: records the call and stores the pack.
     *
     * @param pack the pack argument the native container receives
     */
    public ContainerFixture(final Object pack) {
        constructorCalls++;
        lastPack = pack;
    }

    /**
     * Returns the centre widget the row would join.
     *
     * <p>The fixture answers itself, because it is the only container type the fixture loader
     * knows. The production row refuses a centre that is not a host {@code CContainer}.</p>
     *
     * @return this instance
     */
    public Object getCenter() {
        return this;
    }

    /**
     * Appends one child.
     *
     * @param child the child widget
     */
    public void unaryPlus(final Object child) {
        children.add(child);
    }

    /** @return every appended child, in append order */
    public List<Object> children() {
        return children;
    }

    private final List<Object> children = new java.util.ArrayList<>();
}
