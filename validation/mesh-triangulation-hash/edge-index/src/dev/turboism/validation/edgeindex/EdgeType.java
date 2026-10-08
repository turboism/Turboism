package dev.turboism.validation.edgeindex;

/**
 * Edge type priorities transcribed from the official Cubism 5.3.03
 * {@code MEdge$EdgeType} enum static initializer (javap -c, jar sha256
 * bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166):
 * LOCKED=40, NORMAL=30, USER_TRIANGULATION=20, AUTO_TRIANGULATION=10,
 * NOT_INITIALIZED=-10. {@code EdgeType.a()} returns this byte.
 */
enum EdgeType {
    LOCKED(40),
    NORMAL(30),
    USER_TRIANGULATION(20),
    AUTO_TRIANGULATION(10),
    NOT_INITIALIZED(-10);

    private final byte priority;

    EdgeType(final int priority) {
        this.priority = (byte) priority;
    }

    byte priority() {
        return priority;
    }
}
