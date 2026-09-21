plugins {
    `java-library`
}

dependencies {
    // Internal management contracts shared by :runtime (implementations) and the
    // built-in :plugins:core application. Not a plugin-facing API.
    api(project(":sdk"))
}
