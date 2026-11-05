plugins {
    id("turboism.java-conventions")
}

dependencies {
    // The policy evaluates frozen SDK declarations only. It never sees the runtime,
    // a host artifact, a classloader, a file or the host UI, so every consumer gets
    // the same exact reviewed-version decision.
    api(project(":sdk"))
}
