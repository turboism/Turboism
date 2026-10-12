package dev.turboism.validation.atlasimage;

import java.util.Map;

/** Shared constants only; observer and fixed driver remain separate agents. */
final class AtlasImageObserveContract {
    static final String SCENE = "atlas-image-observe:5303";
    static final String OUTPUT_RELATIVE = "state/atlas-image-observe";
    static final String FIXTURE_SUFFIX = "atlas_mapping_100.cmo3";
    static final String NAMED_PREFIX = "turboism.validation.atlasImageObserve.";
    static final Map<String, String> REVIEWED = Map.ofEntries(
        Map.entry("5000", "42ee222a05478f48739bb1fbf7e7bc7350b21004e506e1b555d1d55c584d99cd"),
        Map.entry("5001", "1aa93b1d9d8da88e8c28de06b680c41a73fc70243307d856d4a2967e6e487a0b"),
        Map.entry("5002", "39e73c8238acba5270422a6e0f228c66a733177d009c710721381285ab0af072"),
        Map.entry("5003", "05bc9b872527fed3dc2641ff833b03d86e6fdafbe6faa294eb3d0afcc0f88a66"),
        Map.entry("5004", "7298c5731525a2bd5ac4e6d5246f1f08603651b0ec37cf5dc3e8ad1802ad8758"),
        Map.entry("5005", "1225893a466d486415773b0a62ec61a75badb2cb5cb2fce8ae744b0123becec4"),
        Map.entry("5006", "cbb9d9ecddb9be0252733ece055ecae7d709de5c56647b7226f6c44607e646f2"),
        Map.entry("5100", "cdda37ed4105cf5e4ecb5811f7065df35b9b9a3319bcb74f8e9189d91e8d3f05"),
        Map.entry("5101", "ddb1f5569bc8da49f3c9f6b6eaec3fe57e4f7abec67c9c44079233ea51056c02"),
        Map.entry("5102", "0567130093015c969d32b91c6a48d4603c28c8224c6a14fa969491f8974f63ca"),
        Map.entry("5103", "a3d916077bd7a176345233da95f1eb0a8bbdf8967fd08162776d5bf6d746009b"),
        Map.entry("5200", "25c41c81ddb3af884b7f0d61a02eba9b09f0412bdc025997c419de7ab7158cf1"),
        Map.entry("5201", "c1b70ca8c571c4f4df4ebce63b3381f6bed1b132ab0ed539046fd0609e2b00ac"),
        Map.entry("5202", "ba1f31a1846490c3f111a4ae99887c36bcc0c1bc1a3cf970eb5a7aeed7902aff"),
        Map.entry("5203", "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"),
        Map.entry("5300", "c89dcaf17b6bfad7227db30bffc2f29cdf49b8e4b0cec1f12af2d85b1c13e767"),
        Map.entry("5301", "694bb49c66ddcf15695dbd8f66e1cbaeb0097f651e2e77c327567244f801d2f4"),
        Map.entry("5302", "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"),
        Map.entry("5303", "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166"),
        Map.entry("5304", "c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d")
    );

    private AtlasImageObserveContract() {}

    static void requireNamedFixture(final String fixture, final String fixtureName,
                                    final String taskId) {
        if (fixture == null || fixture.isBlank() || fixture.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("fixture path is missing or contains NUL");
        }
        final String expectedName = taskId + "-" + FIXTURE_SUFFIX;
        if (!expectedName.equals(fixtureName)) {
            throw new IllegalArgumentException("fixtureName is not the fixed Circle100 task name");
        }
        final int separator = Math.max(fixture.lastIndexOf('/'), fixture.lastIndexOf('\\'));
        if (separator < 0 || separator == fixture.length() - 1) {
            throw new IllegalArgumentException("fixture must be a path to the copied task fixture");
        }
        final String basename = fixture.substring(separator + 1);
        if (!fixtureName.equals(basename)) {
            throw new IllegalArgumentException("fixture basename differs from fixtureName");
        }
    }
}
