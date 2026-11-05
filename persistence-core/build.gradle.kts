plugins {
    id("turboism.java-conventions")
}

dependencies {
    // The kernel is expressed entirely in frozen SDK config/storage records. It has
    // no runtime implementation, host, UI or file-system dependency, so every
    // consumer sees the same value and document types the SDK already exposes.
    api(project(":sdk"))
}
