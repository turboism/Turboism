plugins {
    `java-library`
}

dependencies {
    testImplementation(project(":runtime"))
    testImplementation(project(":sdk"))
    testImplementation(project(":plugins:demo"))
    testImplementation(project(":testframework"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    testImplementation("com.fasterxml.jackson.core:jackson-core:2.17.2")
}

tasks.test {
    systemProperty("projectRoot", rootProject.projectDir.absolutePath)
    systemProperty("sdkBuildDir", project(":sdk").buildDir.absolutePath)
    systemProperty("demoBuildDir", project(":plugins:demo").buildDir.absolutePath)
}
