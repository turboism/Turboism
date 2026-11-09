import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.specs.Spec

plugins {
    id("turboism.plugin-conventions")
}

/** Coordinates of the constrained-Delaunay kernel the UV mesher links against. */
val tinfourGroup = "org.tinfour"

/**
 * Tinfour packages the mesher actually links against. The triangulation kernel needs the shared
 * types, the edge structure, and the standard and semi-virtual implementations with their
 * geometry helpers. The IO, analysis and GIS packages stay out of the archive, together with
 * every META-INF entry, so an embedded payload cannot shadow the plugin descriptor or a signed
 * manifest entry.
 */
val embeddedTinfourPayload = listOf(
    "org/tinfour/common/**",
    "org/tinfour/edge/**",
    "org/tinfour/standard/**",
    "org/tinfour/semivirtual/**",
    "org/tinfour/utils/HilbertSort*",
    "org/tinfour/utils/KahanSummation.class",
    "org/tinfour/utils/Polyside*",
    "org/tinfour/utils/TriangleCollector.class",
    "org/tinfour/utils/Tincalc.class",
    "org/tinfour/vividsolutions/jts/math/**",
    "org/tinfour/voronoi/**"
)

/**
 * The resolved Tinfour artifacts on the compile class path. The archive embeds exactly these
 * bytes, so the plugin cannot drift from the library version the compiler checked.
 */
val tinfourKernel = configurations.compileClasspath.get().incoming
    .artifactView {
        componentFilter(
            Spec { component -> component is ModuleComponentIdentifier && component.group == tinfourGroup }
        )
    }
    .files

dependencies {
    // Tinfour declares JUnit 4 with compile scope in its POM. The plugin needs the kernel only
    // and must not inherit a test framework, so the dependency stays non-transitive.
    implementation(libs.tinfour.core) { isTransitive = false }
}

tasks.named<Jar>("jar") {
    // Declared input: the payload is resolved lazily, so the archive task needs the jar listed
    // explicitly to stay correct for up-to-date checks and the build cache.
    inputs.files(tinfourKernel)
    from(providers.provider { zipTree(tinfourKernel.singleFile) }) {
        include(embeddedTinfourPayload)
        exclude("META-INF/**", "org/tinfour/io/**")
    }
}
