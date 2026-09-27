package dev.turboism.validation.atlasimage.t035;

import java.util.Arrays;

/**
 * T038's finite preflight: helper visibility is checked before the reused
 * T035 bytecode transformer can return a rewritten byte array.
 */
final class T038OfflineAdapter {
    private T038OfflineAdapter() {
    }

    static Decision patch(
        final byte[] input,
        final T038OwnedClassLoader fixtureLoader,
        final T038HelperBinding binding
    ) {
        if (input == null) {
            return Decision.reject(null, "fixture bytes missing");
        }
        if (fixtureLoader == null) {
            return Decision.reject(input, "fixture loader missing");
        }
        final T038HelperBinding.Check check = T038HelperBinding.validate(
            fixtureLoader.visibleHelper(), binding
        );
        if (!check.accepted()) {
            return Decision.reject(input, check.reason());
        }
        final byte[] candidate = T035OwnedTransformer.applyForHelper(
            input,
            true,
            binding.owner(),
            binding.descriptor()
        );
        if (candidate == input || Arrays.equals(candidate, input)) {
            return Decision.reject(input, "fixture identity or shape rejected");
        }
        return Decision.pass(candidate);
    }

    record Decision(byte[] bytes, boolean accepted, String reason) {
        private static Decision pass(final byte[] bytes) {
            return new Decision(bytes, true, "accepted");
        }

        private static Decision reject(final byte[] bytes, final String reason) {
            return new Decision(bytes, false, reason);
        }
    }
}
