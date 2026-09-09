plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":sdk"))
    testImplementation(project(":sdk"))
}
