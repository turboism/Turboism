plugins {
    id("turboism.plugin-conventions")
}

dependencies {
    testImplementation(project(":plugins:atlas-bitmask-nesting"))
    testImplementation(project(":plugins:atlas-dalsoo-polygon"))
}
