import groovy.json.JsonSlurper
import org.gradle.api.tasks.SourceSetContainer
import java.util.zip.ZipFile

/*
 * Turboism cross-platform Java installer (Lane C packaging).
 *
 * Wiring:
 *   stageInstallerPayload  one Gradle-owned staged payload shared by the
 *                          NSIS installer, the Lite/Full ZIPs and the Java
 *                          installer (spec: "One Gradle-owned staged payload").
 *                          Every source/template file, the bootstrap JAR and
 *                          each plugin JAR are declared as Gradle inputs so an
 *                          up-to-date decision can never retain stale payload
 *                          bytes after an input changes.
 *   izPackCreateInstaller  builds build/windows-installer/dist/
 *                          TurboismInstaller-<version>.jar (+ .sha256) with
 *                          the pinned IzPack toolchain. Both the JAR and its
 *                          SHA-256 sidecar are declared task outputs, so
 *                          deleting only the sidecar invalidates the task and
 *                          recreates it on the next invocation. The
 *                          installer.xml is generated from the staged plugin
 *                          JARs' META-INF/turboism/plugin.json — there is no
 *                          manually maintained plugin id list anywhere, and
 *                          plugin projects come from the authoritative Gradle
 *                          project hierarchy (":plugins:*", excluding the
 *                          runtime-owned ":plugins:core"), not a filesystem
 *                          directory scan.
 *   checkJavaInstaller     deterministic non-GUI verification (console
 *                          install/uninstall matrix + locale probes),
 *                          runnable with Java 17 on Linux/macOS/Windows.
 *
 * All three tasks require -PinstallerVersion=<version>.
 */

val installerVersion = providers.gradleProperty("installerVersion")

fun requireInstallerVersion(): String =
    installerVersion.orElse("").get().ifBlank {
        throw GradleException("installer tasks require -PinstallerVersion=<version>")
    }

val payloadDir = layout.buildDirectory.dir("windows-installer/staging")
val distDir = layout.buildDirectory.dir("windows-installer/dist")
val izpackBaseDir = layout.buildDirectory.dir("java-installer/izpack")

// Authoritative plugin inventory: the Gradle project hierarchy declared in
// settings.gradle.kts (":plugins:*"), excluding the runtime-owned core.
val pluginModuleNames: List<String> = rootProject.subprojects
    .map { it.path }
    .filter { it.startsWith(":plugins:") }
    .map { it.removePrefix(":plugins:") }
    .filter { it != "core" }
    .sorted()

val installerTemplateFiles = listOf(
    "LICENSE",
    "packaging/windows-installer/config.template.json",
    "packaging/windows-installer/README.en.txt.template",
    "packaging/windows-installer/README.zh.txt.template",
    "packaging/windows-installer/README.ja.txt.template",
    "packaging/windows-installer/launch-cubism-turboism.bat",
    "packaging/windows-installer/launch-cubism-turboism.ps1",
    "packaging/windows-installer/configure_turboism.ps1",
    "packaging/windows-installer/cubism-launch-common.ps1",
    "packaging/java-installer/uninstall.command",
    "packaging/java-installer/README.java-installer.txt"
)

val customLangPackFiles = listOf(
    "CustomLangPack.xml",
    "CustomLangPack.xml_eng",
    "CustomLangPack.xml_chn",
    "CustomLangPack.xml_jpn"
)

// ---------------------------------------------------------------------------
// Shared payload staging (also consumed by packaging/windows-installer/)
// ---------------------------------------------------------------------------

val stageInstallerPayload by tasks.registering {
    group = "packaging"
    description = "Stages the shared Turboism payload (agent, plugins, docs, launchers) for NSIS, ZIP and Java installer."
    inputs.property("installerVersion", installerVersion)
    // Task providers as inputs: the built bootstrap/plugin JARs (their outputs
    // are tracked without realizing them at configuration time).
    inputs.files(project(":bootstrap").tasks.named("jar"))
    pluginModuleNames.forEach { module ->
        inputs.files(project(":plugins:$module").tasks.named("jar"))
    }
    installerTemplateFiles.forEach { inputs.file(it) }
    outputs.dir(payloadDir)
    dependsOn(project(":bootstrap").tasks.named("jar"))
    pluginModuleNames.forEach { module -> dependsOn(project(":plugins:$module").tasks.named("jar")) }
    doLast {
        val version = requireInstallerVersion()
        val stage = payloadDir.get().asFile
        delete(stage)
        mkdir(stage.resolve("plugins"))

        val agentJar = project(":bootstrap").tasks.named("jar").get().outputs.files.singleFile
        copy {
            from(agentJar)
            into(stage)
            rename { "turboism-agent.jar" }
        }
        pluginModuleNames.forEach { module ->
            val jarTask = project(":plugins:$module").tasks.named("jar").get()
            val jarFile = (jarTask as org.gradle.jvm.tasks.Jar).archiveFile.get().asFile
            copy {
                from(jarFile)
                into(stage.resolve("plugins"))
                rename { "$module.jar" }
            }
        }
        // canonical config template (spec payload list); the ZIPs/NSIS consume
        // config.json generated from it, the Java installer bundles it as the
        // fresh-install seed resource.
        copy {
            from("packaging/windows-installer/config.template.json")
            into(stage)
            rename { "config.template.json" }
        }
        copy {
            from("LICENSE")
            into(stage)
            rename { "LICENSE.txt" }
        }
        listOf(
            "README.en.txt.template" to "README.txt",
            "README.zh.txt.template" to "README.zh.txt",
            "README.ja.txt.template" to "README.ja.txt"
        ).forEach { (template, target) ->
            val text = file("packaging/windows-installer/$template").readText()
                .replace("__VERSION__", version)
            file("$stage/$target").writeText(text)
        }
        // OS-appropriate launcher/configuration files
        copy {
            from("packaging/windows-installer/launch-cubism-turboism.bat")
            from("packaging/windows-installer/launch-cubism-turboism.ps1")
            from("packaging/windows-installer/configure_turboism.ps1")
            from("packaging/windows-installer/cubism-launch-common.ps1")
            from("packaging/java-installer/uninstall.command")
            from("packaging/java-installer/README.java-installer.txt")
            into(stage)
        }
        logger.lifecycle("staged payload: ${stage.absolutePath}")
    }
}

// ---------------------------------------------------------------------------
// Installer listener (install + uninstall side) compiled against izpack-api
// ---------------------------------------------------------------------------

val installerListenerSourceSet = extensions.getByType(SourceSetContainer::class.java).create("installerListener") {
    java.srcDir(file("packaging/java-installer/listener-src"))
}
dependencies.add("installerListenerCompileOnly", "org.codehaus.izpack:izpack-api:5.2.6")
// Pinned IzPack toolchain (frozen spec: org.izpack.gradle:3.2.3 + izpack-ant:5.2.6)
dependencies.add("izpack", "org.codehaus.izpack:izpack-ant:5.2.6")

val installerListenerJarTask = tasks.register<Jar>("installerListenerJar") {
    group = "packaging"
    archiveBaseName.set("turboism-installer-listener")
    destinationDirectory.set(layout.buildDirectory.dir("java-installer/lib"))
    dependsOn(tasks.named("compileInstallerListenerJava"), stageInstallerPayload)
    from(installerListenerSourceSet.output)
    // fresh-install seed: the canonical template from the shared payload
    from(payloadDir) {
        include("config.template.json")
        into("turboism")
    }
}

// Test-only regression harness for the bounded config merge (R8): compiles
// the same listener sources plus the regression main into a separate jar that
// is never embedded in the installer; the Python verifier runs it before the
// live-install matrix (stdlib-only, deterministic).
val installerRegressionSourceSet = extensions.getByType(SourceSetContainer::class.java).create("installerRegression") {
    java.srcDir(file("packaging/java-installer/listener-src"))
    java.srcDir(file("packaging/java-installer/regression-src"))
}
dependencies.add("installerRegressionCompileOnly", "org.codehaus.izpack:izpack-api:5.2.6")

val installerRegressionJarTask = tasks.register<Jar>("installerRegressionJar") {
    group = "packaging"
    archiveBaseName.set("turboism-installer-regression")
    destinationDirectory.set(layout.buildDirectory.dir("java-installer/lib"))
    dependsOn(tasks.named("compileInstallerRegressionJava"))
    from(installerRegressionSourceSet.output)
}

// ---------------------------------------------------------------------------
// Installer XML generation (from staged plugin JAR metadata)
// ---------------------------------------------------------------------------


val generateInstallerXml by tasks.registering {
    group = "packaging"
    description = "Generates installer.xml from the staged plugin JARs' plugin.json metadata."
    dependsOn(stageInstallerPayload, installerListenerJarTask)
    inputs.file("packaging/java-installer/installer.xml.template")
    inputs.dir(payloadDir)
    customLangPackFiles.forEach { inputs.file("packaging/java-installer/$it") }
    outputs.file(izpackBaseDir.map { it.file("installer.xml") })
    outputs.file(izpackBaseDir.map { it.file("CustomLangPack.xml") })
    outputs.file(izpackBaseDir.map { it.file("CustomLangPack.xml_eng") })
    outputs.file(izpackBaseDir.map { it.file("CustomLangPack.xml_chn") })
    outputs.file(izpackBaseDir.map { it.file("CustomLangPack.xml_jpn") })
    doLast {
        val version = requireInstallerVersion()
        val stage = payloadDir.get().asFile
        val izpackDir = izpackBaseDir.get().asFile
        izpackDir.mkdirs()

        data class PluginMeta(
            val id: String,
            val module: String,
            val name: String,
            val version: String,
            val description: String
        )

        val seen = mutableSetOf<String>()
        val plugins = mutableListOf<PluginMeta>()
        stage.resolve("plugins").listFiles { f -> f.name.endsWith(".jar") }
            ?.sortedBy { it.name }
            ?.forEach { jarFile ->
                val meta = ZipFile(jarFile).use { zip ->
                    val entry = zip.getEntry("META-INF/turboism/plugin.json")
                        ?: throw GradleException("${jarFile.name}: missing META-INF/turboism/plugin.json")
                    try {
                        JsonSlurper().parse(zip.getInputStream(entry)) as Map<*, *>
                    } catch (e: Exception) {
                        throw GradleException("${jarFile.name}: malformed META-INF/turboism/plugin.json", e)
                    }
                }
                val id = meta["id"]?.toString()?.trim()
                if (id.isNullOrEmpty()) {
                    throw GradleException("${jarFile.name}: plugin.json has no id")
                }
                if (id == "turboism.core") {
                    throw GradleException("${jarFile.name}: runtime-owned core plugin must not be packaged")
                }
                if (!seen.add(id)) {
                    throw GradleException("duplicate plugin id '$id' in staged payload")
                }
                plugins.add(
                    PluginMeta(
                        id = id,
                        module = jarFile.name.removeSuffix(".jar"),
                        name = meta["name"]?.toString()?.takeIf { it.isNotBlank() } ?: id,
                        version = meta["version"]?.toString() ?: "",
                        description = meta["description"]?.toString() ?: ""
                    )
                )
            }
        if (plugins.isEmpty()) {
            throw GradleException("no first-party plugin JARs found in the staged payload")
        }
        plugins.sortBy { it.id }

        fun xmlEscape(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val stageRel = izpackDir.toPath().toAbsolutePath().normalize()
            .relativize(stage.toPath().toAbsolutePath().normalize())
            .toString().replace('\\', '/')
        val listenerJarRel = izpackDir.toPath().toAbsolutePath().normalize()
            .relativize(installerListenerJarTask.get().archiveFile.get().asFile.toPath().toAbsolutePath().normalize())
            .toString().replace('\\', '/')

        fun titleOf(p: PluginMeta): String = if (p.version.isBlank()) p.name else "${p.name} ${p.version}"

        val pluginPacks = plugins.joinToString("\n") { p ->
            val title = titleOf(p)
            buildString {
                append("        <pack id=\"").append(xmlEscape(p.id))
                append("\" name=\"").append(xmlEscape(title))
                append("\" required=\"no\" preselected=\"true\" installGroups=\"full\">\n")
                append("            <description>").append(xmlEscape(p.description)).append("</description>\n")
                append("            <file src=\"").append(stageRel).append("/plugins/").append(xmlEscape(p.module))
                append(".jar\" targetdir=\"\$INSTALL_PATH/plugins\" override=\"true\"/>\n")
                append("        </pack>")
            }
        }
        val bundled = plugins.joinToString(",") { it.id }

        val xml = file("packaging/java-installer/installer.xml.template").readText()
            .replace("__INSTALLER_VERSION__", version)
            .replace("__STAGE_REL__", stageRel)
            .replace("__LISTENER_JAR_REL__", listenerJarRel)
            .replace("__BUNDLED_PLUGINS__", bundled)
            .replace("__PLUGIN_PACKS__", pluginPacks)
        file("$izpackDir/installer.xml").writeText(xml)
        copy {
            from("packaging/java-installer/CustomLangPack.xml")
            from("packaging/java-installer/CustomLangPack.xml_eng")
            from("packaging/java-installer/CustomLangPack.xml_chn")
            from("packaging/java-installer/CustomLangPack.xml_jpn")
            into(izpackDir)
        }
        logger.lifecycle("installer.xml: ${plugins.size} plugin packs (ids stable by plugin id)")
    }
}

// ---------------------------------------------------------------------------
// izPackCreateInstaller wiring (task itself configured in build.gradle.kts)
// ---------------------------------------------------------------------------

tasks.named("izPackCreateInstaller") {
    dependsOn(generateInstallerXml)
    // the generated installer.xml references these by path; changes must rebuild the jar
    inputs.dir(payloadDir)
    inputs.file(installerListenerJarTask.flatMap { it.archiveFile })
    // The JAR and its SHA-256 sidecar are both declared outputs: deleting only
    // the sidecar marks the task out-of-date and recreates it next invocation.
    outputs.file(distDir.map { it.file("TurboismInstaller-${requireInstallerVersion()}.jar").asFile })
    outputs.file(distDir.map { it.file("TurboismInstaller-${requireInstallerVersion()}.jar.sha256").asFile })
    doLast {
        val jar = distDir.get().file("TurboismInstaller-${requireInstallerVersion()}.jar").asFile
        val shaFile = distDir.get().file("${jar.name}.sha256").asFile
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        jar.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        // sidecar 使用仓库根相对路径（最终验证批次在仓库根执行 sha256sum -c）
        val relPath = rootProject.layout.projectDirectory.asFile.toPath()
            .relativize(jar.toPath()).toString().replace('\\', '/')
        shaFile.writeText(java.util.HexFormat.of().formatHex(digest.digest()) + "  " + relPath + "\n")
        logger.lifecycle("Turboism installer: ${jar.absolutePath} (${jar.length()} bytes)")
    }
}

// ---------------------------------------------------------------------------
// checkJavaInstaller — deterministic non-GUI verification
// ---------------------------------------------------------------------------

val checkJavaInstaller by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the deterministic non-GUI Java installer verification matrix (console mode)."
    dependsOn(tasks.named("izPackCreateInstaller"), installerRegressionJarTask)
    workingDir(rootDir)
    doFirst {
        commandLine(
            "python3",
            "packaging/java-installer/verify-installer.py",
            "--installer", distDir.get().file("TurboismInstaller-${requireInstallerVersion()}.jar").asFile.absolutePath,
            "--sha256", distDir.get().file("TurboismInstaller-${requireInstallerVersion()}.jar.sha256").asFile.absolutePath,
            "--payload", payloadDir.get().asFile.absolutePath,
            "--regression-jar", installerRegressionJarTask.get().archiveFile.get().asFile.absolutePath
        )
    }
}
