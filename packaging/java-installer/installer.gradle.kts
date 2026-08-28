import groovy.json.JsonSlurper
import org.gradle.api.tasks.SourceSetContainer
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.zip.ZipFile

/*
 * Turboism cross-platform Java installer (Lane C packaging).
 *
 * Wiring:
 *   stageInstallerPayload  one Gradle-owned runtime-free Windows stage shared
 *                          by the NSIS installer and Lite/Full ZIPs. Every
 *                          source/template file, the bootstrap JAR and each
 *                          plugin JAR are declared as Gradle inputs so an
 *                          up-to-date decision can never retain stale payload
 *                          bytes after an input changes.
 *   stageJavaInstallerPayload copies the Windows-safe stage into a Java-only
 *                          stage and adds reviewed Linux/macOS managed fx
 *                          payloads; Windows artifacts never consume it.
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
 * All three tasks require matching -PinstallerVersion=<version> and
 * -PturboismRelease=true so published JAR metadata never carries -SNAPSHOT.
 */

val installerVersion = providers.gradleProperty("installerVersion")
val frameworkVersion = rootProject.extra["turboismFrameworkVersion"] as String
val releaseBuild = rootProject.extra["turboismReleaseBuild"] as Boolean
val strictVersion = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")

fun validateInstallerVersion(version: String): String {
    if (!strictVersion.matches(version)) {
        throw GradleException("installerVersion must be strict MAJOR.MINOR.PATCH: $version")
    }
    if (version != frameworkVersion) {
        throw GradleException(
            "installerVersion $version must equal Turboism framework version $frameworkVersion"
        )
    }
    if (!releaseBuild) {
        throw GradleException("installer tasks require -PturboismRelease=true")
    }
    return version
}

fun requireInstallerVersion(): String = validateInstallerVersion(
    installerVersion.orElse("").get().ifBlank {
        throw GradleException("installer tasks require -PinstallerVersion=<version>")
    }
)

fun rejectNonAsciiJsonUnicodeEscapes(bytes: ByteArray, source: String): String {
    val text = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (exception: java.nio.charset.CharacterCodingException) {
        throw GradleException("$source: plugin.json is not valid UTF-8", exception)
    }
    var offset = 0
    while (offset < text.length) {
        if (text[offset] != '\\') {
            offset++
            continue
        }
        var slashCount = 1
        while (offset + slashCount < text.length && text[offset + slashCount] == '\\') {
            slashCount++
        }
        val marker = offset + slashCount
        if (slashCount % 2 == 1 && marker < text.length && text[marker] == 'u') {
            if (marker + 4 >= text.length) {
                throw GradleException("$source: truncated JSON Unicode escape")
            }
            val hex = text.substring(marker + 1, marker + 5)
            if (!Regex("^[0-9A-Fa-f]{4}$").matches(hex)) {
                throw GradleException("$source: JSON Unicode escape must use ASCII hexadecimal digits")
            }
        }
        offset += slashCount
    }
    return text
}

val payloadDir = layout.buildDirectory.dir("windows-installer/staging")
val distDir = layout.buildDirectory.dir("windows-installer/dist")
val izpackBaseDir = layout.buildDirectory.dir("java-installer/izpack")

// Sole release-plugin allowlist authority: packaging/release-plugins.txt (shared
// with the Windows NSIS/ZIP staging; no independent blocklist anywhere — the
// seven public-exclusion modules are simply absent from the manifest). Parsing is
// fail-closed: missing file, blank/comment lines, non-plugin entries,
// duplicates, unsorted order or unknown projects abort the build. The runtime-
// owned :plugins:core stays allowlisted as a project but is never packaged as
// a plugin JAR.
val releasePluginsFile: File = rootProject.file("packaging/release-plugins.txt")

fun parseReleasePluginManifest(file: File): List<String> {
    if (!file.isFile) throw GradleException("release-plugins.txt missing: ${file.path}")
    val lines = file.readLines().map { it.trim() }
    val invalid = lines.filter { it.isEmpty() || it.startsWith("#") }
    if (invalid.isNotEmpty()) {
        throw GradleException("release-plugins.txt forbids blank/comment lines: '${invalid.first()}'")
    }
    val entryPattern = Regex("^:plugins:[a-z0-9-]+$")
    val malformed = lines.filterNot { entryPattern.matches(it) }
    if (malformed.isNotEmpty()) {
        throw GradleException("release-plugins.txt contains a non-plugin entry: '${malformed.first()}'")
    }
    val duplicates = lines.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (duplicates.isNotEmpty()) {
        throw GradleException("release-plugins.txt contains duplicates: '${duplicates.first()}'")
    }
    if (lines != lines.sorted()) {
        throw GradleException("release-plugins.txt entries are not ASCII-sorted")
    }
    val knownProjects = rootProject.subprojects.map { it.path }.toSet()
    val unknown = lines.filterNot { it in knownProjects }
    if (unknown.isNotEmpty()) {
        throw GradleException("release-plugins.txt references unknown project: '${unknown.first()}'")
    }
    return lines
}

val allowedPluginModules: List<String> = parseReleasePluginManifest(releasePluginsFile)
val pluginModuleNames: List<String> = allowedPluginModules
    .map { it.removePrefix(":plugins:") }
    .filter { it != "core" }

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
    "packaging/windows-installer/install-managed-graal.ps1",
    "packaging/java-installer/uninstall.command",
    "packaging/java-installer/README.java-installer.txt"
)

tasks.register("checkInstallerVersion") {
    group = "release verification"
    description = "Verifies the release installer and framework versions are identical and non-SNAPSHOT."
    doLast {
        validateInstallerVersion(requireInstallerVersion())
        if (project(":bootstrap").version.toString() != frameworkVersion) {
            throw GradleException(
                "release bootstrap version ${project(":bootstrap").version} must equal $frameworkVersion"
            )
        }
    }
}

val customLangPackFiles = listOf(
    "CustomLangPack.xml",
    "CustomLangPack.xml_eng",
    "CustomLangPack.xml_chn",
    "CustomLangPack.xml_jpn"
)

val fxRuntimeRoot = rootProject.file("packaging/fx-runtime")
val fxRuntimeManifestFile = fxRuntimeRoot.resolve("manifest.properties")
val fxRuntimePlatforms = listOf(
    "linux-x86_64",
    "linux-aarch64",
    "macos-x86_64",
    "macos-aarch64"
)
val fxRuntimeCache = layout.buildDirectory.dir("fx-runtime/cache")
val fxRuntimeStage = layout.buildDirectory.dir("fx-runtime/staging")
val fxReleaseHost = "github.com"
val fxReleaseAssetHost = "release-assets.githubusercontent.com"
val fxDownloadConnectTimeoutMs = 15_000
val fxDownloadReadTimeoutMs = 60_000
val fxArchiveMaxBytes = 64L * 1024L * 1024L

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

fun loadFxRuntimeManifest(): Properties {
    if (!fxRuntimeManifestFile.isFile) {
        throw GradleException("managed fx manifest missing: ${fxRuntimeManifestFile.path}")
    }
    return Properties().apply {
        fxRuntimeManifestFile.inputStream().use(::load)
    }
}

fun openFxDownload(source: URI, approvedHost: String): HttpURLConnection {
    if (source.scheme != "https" || source.host != approvedHost) {
        throw GradleException("managed fx download source is not approved: $source")
    }
    return (source.toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = fxDownloadConnectTimeoutMs
        readTimeout = fxDownloadReadTimeoutMs
        requestMethod = "GET"
        setRequestProperty("Accept", "application/octet-stream")
    }
}

fun downloadFxArchive(
    source: URI,
    target: File,
    expectedBytes: Long,
    expectedReleaseAssetPath: String
) {
    if (expectedBytes <= 0L || expectedBytes > fxArchiveMaxBytes) {
        throw GradleException("managed fx archive size is outside the reviewed limit: $expectedBytes")
    }
    var connection = openFxDownload(source, fxReleaseHost)
    try {
        var status = connection.responseCode
        if (status in 300..399) {
            val location = connection.getHeaderField("Location")
                ?: throw GradleException("managed fx redirect has no Location header")
            val redirected = source.resolve(location)
            if (redirected.scheme != "https"
                || redirected.host != fxReleaseAssetHost
                || redirected.port != -1
                || redirected.userInfo != null
                || redirected.fragment != null
                || redirected.rawPath != expectedReleaseAssetPath) {
                throw GradleException("managed fx redirect is not the reviewed release asset")
            }
            connection.disconnect()
            connection = openFxDownload(redirected, fxReleaseAssetHost)
            status = connection.responseCode
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw GradleException("managed fx download failed with HTTP $status: $source")
        }
        val contentLength = connection.contentLengthLong
        if (contentLength != expectedBytes) {
            throw GradleException(
                "managed fx archive content length mismatch: $contentLength != $expectedBytes"
            )
        }
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > expectedBytes) {
                        throw GradleException("managed fx archive exceeds its reviewed size")
                    }
                    output.write(buffer, 0, read)
                }
                if (copied != expectedBytes) {
                    throw GradleException(
                        "managed fx archive size mismatch: $copied != $expectedBytes"
                    )
                }
            }
        }
    } finally {
        connection.disconnect()
    }
}

fun activateFxArchive(temporary: File, archive: File) {
    try {
        Files.move(
            temporary.toPath(),
            archive.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (exception: AtomicMoveNotSupportedException) {
        throw GradleException("managed fx cache does not support atomic activation: $archive", exception)
    }
}

val stageFxRuntimePayload by tasks.registering {
    group = "packaging"
    description = "Downloads, verifies and stages the reviewed offline fx runtime payloads."
    inputs.file(fxRuntimeManifestFile)
    inputs.file(fxRuntimeRoot.resolve("LICENSE"))
    inputs.file(fxRuntimeRoot.resolve("THIRD_PARTY_NOTICES.md"))
    inputs.file(fxRuntimeRoot.resolve("TURBOISM-DISTRIBUTION-NOTICE.txt"))
    outputs.dir(fxRuntimeStage)
    doLast {
        val manifest = loadFxRuntimeManifest()
        val fxVersion = manifest.getProperty("fxVersion")
            ?: throw GradleException("managed fx manifest has no fxVersion")
        if (fxVersion != "0.0.5") {
            throw GradleException("managed fx manifest version is unsupported: $fxVersion")
        }
        val sourceCommit = manifest.getProperty("sourceCommit")
        if (sourceCommit != "df7e6245e1992758d4060c97477ceafa27770551") {
            throw GradleException("managed fx source commit is not the reviewed v0.0.5 commit")
        }
        val license = fxRuntimeRoot.resolve("LICENSE")
        val notices = fxRuntimeRoot.resolve("THIRD_PARTY_NOTICES.md")
        if (sha256(license) != manifest.getProperty("licenseSha256")) {
            throw GradleException("managed fx LICENSE hash mismatch")
        }
        if (sha256(notices) != manifest.getProperty("thirdPartyNoticesSha256")) {
            throw GradleException("managed fx THIRD_PARTY_NOTICES.md hash mismatch")
        }

        val cache = fxRuntimeCache.get().asFile
        val stage = fxRuntimeStage.get().asFile
        cache.mkdirs()
        delete(stage)
        stage.mkdirs()
        fxRuntimePlatforms.forEach { platform ->
            val archiveName = manifest.getProperty("$platform.archive")
                ?: throw GradleException("managed fx manifest is missing $platform.archive")
            if (!Regex("^fx-(?:linux|macos)-(?:x86_64|aarch64)\\.tar\\.gz$")
                    .matches(archiveName)) {
                throw GradleException("managed fx archive name is invalid: $archiveName")
            }
            val archiveSha256 = manifest.getProperty("$platform.archiveSha256")
                ?: throw GradleException("managed fx manifest is missing $platform.archiveSha256")
            val archiveSize = manifest.getProperty("$platform.archiveSize")?.toLongOrNull()
                ?: throw GradleException("managed fx manifest has invalid $platform.archiveSize")
            val releaseAssetPath = manifest.getProperty("$platform.releaseAssetPath")
                ?: throw GradleException("managed fx manifest is missing $platform.releaseAssetPath")
            val executableSha256 = manifest.getProperty("$platform.executableSha256")
                ?: throw GradleException("managed fx manifest is missing $platform.executableSha256")
            val executableSize = manifest.getProperty("$platform.executableSize")?.toLongOrNull()
                ?: throw GradleException("managed fx manifest has invalid $platform.executableSize")
            val archive = cache.resolve(archiveName)
            if (!archive.isFile || sha256(archive) != archiveSha256) {
                val temporary = cache.resolve(".$archiveName.part")
                temporary.delete()
                try {
                    downloadFxArchive(
                        URI("https://github.com/vercel-labs/fx/releases/download/v$fxVersion/$archiveName"),
                        temporary,
                        archiveSize,
                        releaseAssetPath
                    )
                    if (sha256(temporary) != archiveSha256) {
                        throw GradleException("managed fx archive hash mismatch: $archiveName")
                    }
                    activateFxArchive(temporary, archive)
                } finally {
                    temporary.delete()
                }
            }

            val target = stage.resolve(platform)
            target.mkdirs()
            copy {
                from(tarTree(resources.gzip(archive))) {
                    include("fx", "LICENSE", "THIRD_PARTY_NOTICES.md")
                }
                into(target)
            }
            val executable = target.resolve("fx")
            if (!executable.isFile || executable.length() != executableSize
                || sha256(executable) != executableSha256) {
                throw GradleException("managed fx executable verification failed for $platform")
            }
            executable.setExecutable(true, true)
            copy {
                from(fxRuntimeManifestFile)
                from(fxRuntimeRoot.resolve("TURBOISM-DISTRIBUTION-NOTICE.txt"))
                into(target)
            }
        }
        logger.lifecycle("staged managed fx runtimes: ${stage.absolutePath}")
    }
}

// ---------------------------------------------------------------------------
// Plugin metadata parser contract and shared Windows payload staging
// ---------------------------------------------------------------------------

val checkInstallerPluginJsonUnicodeEscapes by tasks.registering {
    group = "verification"
    description = "Pins ASCII-only JSON Unicode escapes for installer plugin metadata."
    doLast {
        val valid = rejectNonAsciiJsonUnicodeEscapes(
            "{\"id\":\"dev.turboism.plugin.\\u006dcp\"}".toByteArray(StandardCharsets.UTF_8),
            "valid fixture"
        )
        val parsed = JsonSlurper().parseText(valid) as Map<*, *>
        if (parsed["id"] != "dev.turboism.plugin.mcp") {
            throw GradleException("ASCII plugin.json Unicode escape did not parse canonically")
        }
        listOf(
            "{\"id\":\"dev.turboism.plugin.\\u٠٠٦dcp\"}" to "Arabic-Indic",
            "{\"id\":\"dev.turboism.plugin.\\u００６dcp\"}" to "fullwidth"
        ).forEach { (fixture, name) ->
            try {
                rejectNonAsciiJsonUnicodeEscapes(
                    fixture.toByteArray(StandardCharsets.UTF_8),
                    "$name fixture"
                )
                throw GradleException("$name plugin.json Unicode escape was accepted")
            } catch (expected: GradleException) {
                if (!expected.message.orEmpty().contains("ASCII hexadecimal")) {
                    throw expected
                }
            }
        }
    }
}

val stageInstallerPayload by tasks.registering {
    group = "packaging"
    description = "Stages the shared Turboism payload (agent, plugins, docs, launchers) for NSIS, ZIP and Java installer."
    inputs.property("installerVersion", installerVersion)
    // The release-plugin allowlist is an incremental task input: editing it
    // invalidates the staged payload.
    inputs.file(releasePluginsFile)
    // Task providers as inputs: the built bootstrap/plugin JARs (their outputs
    // are tracked without realizing them at configuration time).
    inputs.files(project(":bootstrap").tasks.named("jar"))
    inputs.files(project(":graal-host").tasks.named("windowsPreviewDist"))
    pluginModuleNames.forEach { module ->
        inputs.files(project(":plugins:$module").tasks.named("jar"))
    }
    installerTemplateFiles.forEach { inputs.file(it) }
    outputs.dir(payloadDir)
    dependsOn(project(":bootstrap").tasks.named("jar"))
    dependsOn(project(":graal-host").tasks.named("windowsPreviewDist"))
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
        val graalHost = project(":graal-host").layout.buildDirectory
            .dir("windows-preview/graal-host/lib").get().asFile
        if (!graalHost.isDirectory) {
            throw GradleException("Windows Graal host closure is missing: ${graalHost.absolutePath}")
        }
        copy {
            from(graalHost)
            into(stage.resolve("graal/lib"))
        }
        val requiredGraalLibraries = listOf(
            "graal-host-", "jackson-annotations-", "jackson-core-", "jackson-databind-",
            "collections-", "jniutils-", "js-isolate-windows-amd64-community-",
            "nativebridge-", "nativeimage-", "polyglot-", "truffle-api-", "word-"
        )
        val stagedGraalLibraries = stage.resolve("graal/lib").listFiles()?.map { it.name }.orEmpty()
        val missingGraalLibraries = requiredGraalLibraries.filter { prefix ->
            stagedGraalLibraries.none { it.startsWith(prefix) && it.endsWith(".jar") }
        }
        if (missingGraalLibraries.isNotEmpty()) {
            throw GradleException("Staged Windows Graal host closure is incomplete: $missingGraalLibraries")
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
        // Managed fx runtimes are intentionally absent from this shared Windows stage.
        // ZIP/NSIS Full carries the complete plugin roster, including Turboism with fx,
        // but Windows has no reviewed managed fx executable; Thin/custom-executable use
        // remains available without mispackaging Linux/macOS binaries into Windows assets.
        // OS-appropriate launcher/configuration files
        copy {
            from("packaging/windows-installer/launch-cubism-turboism.bat")
            from("packaging/windows-installer/launch-cubism-turboism.ps1")
            from("packaging/windows-installer/configure_turboism.ps1")
            from("packaging/windows-installer/cubism-launch-common.ps1")
            from("packaging/windows-installer/install-managed-graal.ps1")
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
    from(fxRuntimeManifestFile) {
        into("turboism/fx-runtime")
    }
    from(rootProject.file("packaging/fx-runtime")) {
        include(
            "LICENSE",
            "THIRD_PARTY_NOTICES.md",
            "TURBOISM-DISTRIBUTION-NOTICE.txt"
        )
        into("turboism/fx-runtime")
    }
}

// Test-only regression harness for the bounded config merge and listener
// policy: compiles the same listener sources plus the regression main into a
// separate jar that is never embedded in the installer; the Python verifier
// runs it before the live-install matrix.
val installerRegressionSourceSet = extensions.getByType(SourceSetContainer::class.java).create("installerRegression") {
    java.srcDir(file("packaging/java-installer/listener-src"))
    java.srcDir(file("packaging/java-installer/regression-src"))
}
dependencies.add("installerRegressionCompileOnly", "org.codehaus.izpack:izpack-api:5.2.6")
dependencies.add("installerRegressionCompileOnly", "org.codehaus.izpack:izpack-tools:5.2.6")

val installerRegressionJarTask = tasks.register<Jar>("installerRegressionJar") {
    group = "packaging"
    archiveBaseName.set("turboism-installer-regression")
    destinationDirectory.set(layout.buildDirectory.dir("java-installer/lib"))
    dependsOn(tasks.named("compileInstallerRegressionJava"))
    from(installerRegressionSourceSet.output)
    // The behavioral listener regression uses an InstallData proxy, whose full
    // IzPack API signature includes Platform from izpack-tools. Bundle these
    // test-only API classes so the verifier remains a one-jar executable.
    from(installerRegressionSourceSet.compileClasspath.files.map { zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // The behavioral listener regression initializes the same managed-fx
    // identity table as the shipped listener, so include its exact resources.
    from(fxRuntimeManifestFile) {
        into("turboism/fx-runtime")
    }
    from(rootProject.file("packaging/fx-runtime")) {
        include(
            "LICENSE",
            "THIRD_PARTY_NOTICES.md",
            "TURBOISM-DISTRIBUTION-NOTICE.txt"
        )
        into("turboism/fx-runtime")
    }
}

// ---------------------------------------------------------------------------
// Installer XML generation (from staged plugin JAR metadata)
// ---------------------------------------------------------------------------


val javaInstallerPayloadDir = layout.buildDirectory.dir("java-installer/staging")

val stageJavaInstallerPayload by tasks.registering {
    group = "packaging"
    description = "Adds reviewed Linux/macOS managed fx runtimes to the Java installer payload."
    dependsOn(stageInstallerPayload, stageFxRuntimePayload)
    inputs.dir(payloadDir)
    inputs.dir(fxRuntimeStage)
    outputs.dir(javaInstallerPayloadDir)
    doLast {
        val target = javaInstallerPayloadDir.get().asFile
        delete(target)
        copy {
            from(payloadDir)
            into(target)
        }
        copy {
            from(fxRuntimeStage)
            into(target.resolve("runtimes/fx/0.0.5"))
        }
    }
}

val generateInstallerXml by tasks.registering {
    group = "packaging"
    description = "Generates installer.xml from the staged plugin JARs' plugin.json metadata."
    dependsOn(stageJavaInstallerPayload, installerListenerJarTask)
    inputs.file("packaging/java-installer/installer.xml.template")
    inputs.dir(javaInstallerPayloadDir)
    customLangPackFiles.forEach { inputs.file("packaging/java-installer/$it") }
    outputs.file(izpackBaseDir.map { it.file("installer.xml") })
    outputs.file(izpackBaseDir.map { it.file("CustomLangPack.xml") })
    outputs.file(izpackBaseDir.map { it.file("CustomLangPack.xml_eng") })
    outputs.file(izpackBaseDir.map { it.file("CustomLangPack.xml_chn") })
    outputs.file(izpackBaseDir.map { it.file("CustomLangPack.xml_jpn") })
    doLast {
        val version = requireInstallerVersion()
        val stage = javaInstallerPayloadDir.get().asFile
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
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val canonical = rejectNonAsciiJsonUnicodeEscapes(
                            bytes,
                            "${jarFile.name}: META-INF/turboism/plugin.json"
                        )
                        JsonSlurper().parseText(canonical) as Map<*, *>
                    } catch (e: GradleException) {
                        throw e
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

        // r6: JAR 拷贝与插件勾选分离 —— 一个 required Full-only 载荷 pack 携带全部
        // 插件 JAR（勾选无法阻止安装）；每个插件一个 metadata-only 选择 pack，
        // 其 id == 插件 id，是监听器识别 disabledPlugins 的唯一选择身份。
        val payloadPack = buildString {
            append("        <pack id=\"turboism-plugin-payload\" name=\"Turboism Plugins\" required=\"true\" installGroups=\"full,thin\">\n")
            append("            <description>All bundled first-party plugin JARs. Plugin selection packs control disabledPlugins only.</description>\n")
            plugins.forEach { p ->
                append("            <file src=\"").append(stageRel).append("/plugins/").append(xmlEscape(p.module))
                append(".jar\" targetdir=\"\$INSTALL_PATH/plugins\" override=\"true\"/>\n")
            }
            append("        </pack>")
        }
        // r1: 选择 pack 是 metadata-only（全部插件 JAR 由 required 的 payload pack 安装，
        // 勾选只控制 disabledPlugins），IzPack 按无文件计算 0 KB 会让用户误以为异常。
        // 说明中追加多语言备注（installer.xml 内联三语为兜底；en/zh/ja 经 CustomLangPack
        // <pluginId>.description 覆盖为单语言文案，见下方 langpack 注入）。
        val noteEn = "The plugin JAR is installed with the Turboism Plugins payload; the checkbox only controls the enabled list."
        val noteZh = "插件 JAR 已随 Turboism Plugins 载荷一并安装；勾选仅控制启用列表。"
        val noteJa = "プラグイン JAR は Turboism Plugins ペイロードに含めてインストールされます。チェックボックスは有効化リストの制御のみです。"
        val selectionPacks = plugins.joinToString("\n") { p ->
            val title = titleOf(p)
            buildString {
                append("        <pack id=\"").append(xmlEscape(p.id))
                append("\" name=\"").append(xmlEscape(title))
                append("\" required=\"no\" preselected=\"true\" installGroups=\"full,thin\">\n")
                append("            <description>").append(xmlEscape(p.description + " " + noteEn + " / " + noteZh + " / " + noteJa)).append("</description>\n")
                append("        </pack>")
            }
        }
        val runtimePack = buildString {
            append("        <pack id=\"turboism-fx-runtime\" name=\"Managed fx Runtime\" required=\"true\" installGroups=\"full\">\n")
            append("            <description>Reviewed platform-specific fx v0.0.5 runtime for Turboism with fx.</description>\n")
            append("            <fileset dir=\"").append(stageRel)
                .append("/runtimes/fx/0.0.5/linux-x86_64\" targetdir=\"\$INSTALL_PATH/runtimes/fx/0.0.5/linux-x86_64\" override=\"true\" os=\"unix\">\n")
            append("                <exclude name=\"fx\"/>\n")
            append("            </fileset>\n")
            append("            <file src=\"").append(stageRel)
                .append("/runtimes/fx/0.0.5/linux-x86_64/fx\" targetdir=\"\$INSTALL_PATH/runtimes/fx/0.0.5/linux-x86_64\" override=\"true\" os=\"unix\"/>")
            append("\n")
            append("            <fileset dir=\"").append(stageRel)
                .append("/runtimes/fx/0.0.5/linux-aarch64\" targetdir=\"\$INSTALL_PATH/runtimes/fx/0.0.5/linux-aarch64\" override=\"true\" os=\"unix\">\n")
            append("                <exclude name=\"fx\"/>\n")
            append("            </fileset>\n")
            append("            <file src=\"").append(stageRel)
                .append("/runtimes/fx/0.0.5/linux-aarch64/fx\" targetdir=\"\$INSTALL_PATH/runtimes/fx/0.0.5/linux-aarch64\" override=\"true\" os=\"unix\"/>\n")
            append("            <fileset dir=\"").append(stageRel)
                .append("/runtimes/fx/0.0.5/macos-x86_64\" targetdir=\"\$INSTALL_PATH/runtimes/fx/0.0.5/macos-x86_64\" override=\"true\" os=\"mac\">\n")
            append("                <exclude name=\"fx\"/>\n")
            append("            </fileset>\n")
            append("            <file src=\"").append(stageRel)
                .append("/runtimes/fx/0.0.5/macos-x86_64/fx\" targetdir=\"\$INSTALL_PATH/runtimes/fx/0.0.5/macos-x86_64\" override=\"true\" os=\"mac\"/>\n")
            append("            <fileset dir=\"").append(stageRel)
                .append("/runtimes/fx/0.0.5/macos-aarch64\" targetdir=\"\$INSTALL_PATH/runtimes/fx/0.0.5/macos-aarch64\" override=\"true\" os=\"mac\">\n")
            append("                <exclude name=\"fx\"/>\n")
            append("            </fileset>\n")
            append("            <file src=\"").append(stageRel)
                .append("/runtimes/fx/0.0.5/macos-aarch64/fx\" targetdir=\"\$INSTALL_PATH/runtimes/fx/0.0.5/macos-aarch64\" override=\"true\" os=\"mac\"/>\n")
            append("        </pack>")
        }
        val pluginPacks = payloadPack + "\n" + runtimePack + "\n" + selectionPacks
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
        // r1: 为每个插件选择 pack 注入本地化描述。IzPack 5.2.6 的
        // PackHelper.getPackDescription 优先查 langpack 的 "<packId>.description" 键
        // （<pack id> 即 langPackId），缺失时才回退 installer.xml 内联 <description>。
        // 仅在生成的副本上追加，仓库内的 CustomLangPack 源文件保持原样。
        listOf(
            "CustomLangPack.xml" to noteEn,
            "CustomLangPack.xml_eng" to noteEn,
            "CustomLangPack.xml_chn" to noteZh,
            "CustomLangPack.xml_jpn" to noteJa
        ).forEach { (file, localeNote) ->
            val target = izpackDir.resolve(file)
            val entries = plugins.joinToString("\n") { p ->
                "    <str id=\"" + xmlEscape(p.id) + ".description\" txt=\"" +
                    xmlEscape(p.description + " — " + localeNote) + "\"/>"
            }
            target.writeText(target.readText().replace("</izpack:langpack>", entries + "\n</izpack:langpack>"))
        }
        logger.lifecycle("installer.xml: ${plugins.size} plugin selection packs + required plugin and managed fx payload packs")
    }
}

// ---------------------------------------------------------------------------
// izPackCreateInstaller wiring (task itself configured in build.gradle.kts)
// ---------------------------------------------------------------------------

tasks.named("izPackCreateInstaller") {
    dependsOn(generateInstallerXml)
    // the generated installer.xml references these by path; changes must rebuild the jar
    inputs.dir(javaInstallerPayloadDir)
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
        // 只记录同目录文件名，使发布附件下载后可直接运行 sha256sum -c。
        shaFile.writeText(
            java.util.HexFormat.of().formatHex(digest.digest()) + "  " + jar.name + "\n"
        )
        logger.lifecycle("Turboism installer: ${jar.absolutePath} (${jar.length()} bytes)")
    }
}

// ---------------------------------------------------------------------------
// checkJavaInstaller — deterministic non-GUI verification
// ---------------------------------------------------------------------------

val checkJavaInstaller by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the deterministic non-GUI Java installer verification matrix (console mode)."
    dependsOn(
        tasks.named("izPackCreateInstaller"),
        installerRegressionJarTask,
        checkInstallerPluginJsonUnicodeEscapes
    )
    workingDir(rootDir)
    doFirst {
        commandLine(
            "python3",
            "packaging/java-installer/verify-installer.py",
            "--installer", distDir.get().file("TurboismInstaller-${requireInstallerVersion()}.jar").asFile.absolutePath,
            "--sha256", distDir.get().file("TurboismInstaller-${requireInstallerVersion()}.jar.sha256").asFile.absolutePath,
            "--payload", javaInstallerPayloadDir.get().asFile.absolutePath,
            "--regression-jar", installerRegressionJarTask.get().archiveFile.get().asFile.absolutePath,
            "--manifest", releasePluginsFile.absolutePath
        )
    }
}
