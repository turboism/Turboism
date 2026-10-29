package dev.turboism.sdk.plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Test fixture: an SDK-packaged interface so {@code PluginGenerationGuard} treats it as a
 * gateable handle. {@code close()} is the terminal operation; {@code mutate} is ordinary
 * admission-gated work; {@code child()} exercises recursive handle wrapping.
 */
public interface GuardedServiceFixture extends Registration {

    String mutate(String value);

    GuardedServiceFixture child();

    /** Same-named verb with an argument: never a terminal operation, stays fenced. */
    default void close(final String reason) {}

    default byte[] transferBytes(final byte[] input) {
        return input;
    }

    default byte[][] transferMatrix(final byte[][] input) {
        return input;
    }

    default GuardedServiceFixture[] transferArray(final GuardedServiceFixture[] input) {
        return input;
    }

    default Optional<GuardedServiceFixture> optionalChild() {
        return Optional.of(child());
    }

    default List<GuardedServiceFixture> listChildren() {
        return List.of(child());
    }

    default Set<GuardedServiceFixture> setChildren() {
        return Set.of(child());
    }

    default Map<String, GuardedServiceFixture> mapChildren() {
        return Map.of("key", child());
    }

    default Map<String, List<GuardedServiceFixture>> nestedCarrierChildren() {
        return Map.of("group", List.of(child()));
    }

    default CompletionStage<GuardedServiceFixture> stageChild() {
        return CompletableFuture.completedFuture(child());
    }

    default Set<String> unchangedSet() {
        return Set.of("a", "b");
    }

    default Map<Integer, String> unchangedMap() {
        return Map.of(1, "marker1", 2, "marker2");
    }

    default Optional<String> unchangedOptional() {
        return Optional.of("constant");
    }

    default List<String> unchangedList() {
        return List.of("alpha", "beta");
    }

    default void acceptOptional(final Optional<GuardedServiceFixture> optional) {}

    default void acceptList(final List<GuardedServiceFixture> list) {}

    default void acceptSet(final Set<GuardedServiceFixture> set) {}

    default void acceptMap(final Map<String, GuardedServiceFixture> map) {}

    default List<GuardedServiceFixture> mixedList() {
        return List.of(child());
    }

    default Set<GuardedServiceFixture> mixedSet() {
        return Set.of(child());
    }

    default Map<String, GuardedServiceFixture> mixedMap() {
        return Map.of("first", child());
    }
}
