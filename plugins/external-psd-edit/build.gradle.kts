plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":sdk"))
    annotationProcessor(project(":event-processor"))
    testImplementation(project(":sdk"))
}
