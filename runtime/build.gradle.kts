plugins {
    `java-library`
}

dependencies {
    implementation(project(":sdk"))

    // JSON parsing implementation stays in runtime, not in SDK
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.17.2")

    // Runtime-only bytecode metadata scanning for the local draft mapping review pipeline.
    implementation("org.ow2.asm:asm:9.7.1")

    implementation("io.github.resilience4j:resilience4j-bulkhead:2.1.0")
    implementation("io.github.resilience4j:resilience4j-timelimiter:2.1.0")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.1.0")
}
