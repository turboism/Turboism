import dev.turboism.gradle.internal.VerificationStamps
import org.gradle.api.artifacts.ProjectDependency

/*
 * Architecture dependency inventory (#70, step 1).
 *
 * `checkModuleBoundaries` answers "is this direction allowed?". This inventory
 * answers "what are the directions today?": it materialises the authoritative
 * Gradle project graph (including edges that convention plugins add, which no
 * `build.gradle.kts` text scan can see) and hands it to
 * `scripts/dev/dependency_inventory.py`, which adds the `:runtime` package graph
 * and its dependency cycles.
 *
 * The committed baseline is `architecture/dependency-inventory.json`. The gate
 * fails closed when the graph drifts, so a new module edge or a new package
 * cycle is a reviewed change instead of an accident.
 */

private val inventoryConfigurations = listOf(
    "api",
    "implementation",
    "compileOnly",
    "compileOnlyApi",
    "runtimeOnly",
    "annotationProcessor",
    "testImplementation",
    "testCompileOnly",
    "testRuntimeOnly"
)

val dependencyInventoryProjectGraph =
    layout.buildDirectory.file("architecture/dependency-inventory-project-graph.json")

val dependencyInventoryRaw by tasks.registering {
    group = "verification"
    description = "Writes the authoritative Gradle project dependency graph for the architecture inventory."
    outputs.file(dependencyInventoryProjectGraph)
    doLast {
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val modules = rootProject.subprojects
            .sortedBy { it.path }
            .joinToString(",\n") { subproject ->
                val configurations = inventoryConfigurations.sorted().mapNotNull { name ->
                    val configuration = subproject.configurations.findByName(name) ?: return@mapNotNull null
                    val dependencies = configuration.dependencies
                        .withType(ProjectDependency::class.java)
                        .map { it.dependencyProject.path }
                        .distinct()
                        .sorted()
                    if (dependencies.isEmpty()) {
                        null
                    } else {
                        "      " + quote(name) + ": [" + dependencies.joinToString(", ") { quote(it) } + "]"
                    }
                }
                "    " + quote(subproject.path) + ": {\n" +
                    configurations.joinToString(",\n") + (if (configurations.isEmpty()) "" else "\n") +
                    "    }"
            }
        val target = dependencyInventoryProjectGraph.get().asFile
        target.parentFile.mkdirs()
        target.writeText(
            "{\n  \"format\": \"turboism-project-graph-v1\",\n  \"modules\": {\n" +
                modules + "\n  }\n}\n",
            Charsets.UTF_8
        )
    }
}

tasks.register<Exec>("checkDependencyInventory") {
    group = "verification"
    description = "Verifies the committed module graph and :runtime package cycle baseline."
    workingDir(rootDir)
    dependsOn(dependencyInventoryRaw)
    inputs.file("scripts/dev/dependency_inventory.py")
    inputs.file("architecture/dependency-inventory.json")
    inputs.files(fileTree("runtime/src/main/java") { include("**/*.java") })
    VerificationStamps.apply(this)
    commandLine(
        "python3",
        "scripts/dev/dependency_inventory.py",
        "--check",
        "--project-graph",
        dependencyInventoryProjectGraph.get().asFile.absolutePath
    )
}
