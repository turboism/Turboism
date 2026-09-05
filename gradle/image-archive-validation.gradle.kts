import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.util.jar.JarFile

val compileImageArchiveHostProbe by tasks.registering(JavaCompile::class) {
    group = "host verification"
    description = "Compiles the JDK-only native image archive auxiliary validator; never launches Cubism."
    source(fileTree("testing/host-validation/image-archive/src") { include("**/*.java") })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("image-archive-host-probe/classes"))
    javaCompiler.set(project.extensions.getByType<JavaToolchainService>().compilerFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
    options.release.set(17)
    options.encoding = "UTF-8"
}

val buildImageArchiveHostProbe by tasks.registering(Jar::class) {
    group = "host verification"
    description = "Builds the isolated native PNG archive validator; excluded from release artifacts."
    dependsOn(compileImageArchiveHostProbe)
    archiveFileName.set("image-archive-host-validation-exerciser.jar")
    destinationDirectory.set(layout.buildDirectory)
    from(compileImageArchiveHostProbe.flatMap { it.destinationDirectory })
    manifest { attributes("Premain-Class" to "dev.turboism.validation.ImageArchiveReuseHostAgent") }
}

tasks.register("checkImageArchiveValidationBundle") {
    group = "verification"
    description = "Checks native PNG reuse code is packaged and its validator is not in the product."
    dependsOn("previewBundle", buildImageArchiveHostProbe)
    doLast {
        val agent = project(":bootstrap").tasks.named<Jar>("jar").get().archiveFile.get().asFile
        JarFile(agent).use { jar ->
            check(jar.getJarEntry("dev/turboism/adapter/cubism/optimization/image/ImageArchiveReuseBridge.class") != null)
            check(jar.getJarEntry("dev/turboism/bootstrap/VerifiedImageArchiveReuseInstaller.class") != null)
            check(jar.getJarEntry("dev/turboism/validation/ImageArchiveReuseHostAgent.class") == null)
        }
        JarFile(buildImageArchiveHostProbe.get().archiveFile.get().asFile).use { jar ->
            check(jar.manifest.mainAttributes.getValue("Premain-Class") == "dev.turboism.validation.ImageArchiveReuseHostAgent")
        }
    }
}
