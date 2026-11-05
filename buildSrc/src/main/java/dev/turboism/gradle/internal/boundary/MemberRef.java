package dev.turboism.gradle.internal.boundary;

/**
 * One constant-pool member reference of a compiled class: the owner's binary name as it is stored
 * in the pool ({@code java/nio/file/Files}), the member name ({@code write}, {@code <init>}) and the
 * type descriptor.
 *
 * <p>The owner is deliberately kept in its on-disk binary form rather than a dotted class name: the
 * permission rules are keyed to it, and no resolution or class loading happens anywhere in the
 * audit.
 */
public record MemberRef(String owner, String name, String descriptor) {
}
