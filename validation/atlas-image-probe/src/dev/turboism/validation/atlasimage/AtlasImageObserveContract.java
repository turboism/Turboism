package dev.turboism.validation.atlasimage;

/** Shared constants only; observer and fixed driver remain separate agents. */
final class AtlasImageObserveContract {
    static final String SCENE = "atlas-image-observe:5303";
    static final String OUTPUT_RELATIVE = "state/atlas-image-observe";
    static final String FIXTURE_SUFFIX = "atlas_mapping_100.cmo3";
    static final String NAMED_PREFIX = "turboism.validation.atlasImageObserve.";

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
