import org.gradle.jvm.tasks.Jar

plugins {
    id("turboism.java-conventions")
    `maven-publish`
}

dependencies {
    implementation(project(":sdk"))
    testImplementation(project(":sdk"))
}

// Published developer coordinates must not embed the per-worktree classifier.
tasks.named<Jar>("jar") {
    archiveClassifier.set("")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
