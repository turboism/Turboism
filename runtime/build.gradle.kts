plugins {
    `java-library`
}

dependencies {
    implementation(project(":sdk"))

    // JSON parsing implementation stays in runtime, not in SDK
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.17.2")
}
