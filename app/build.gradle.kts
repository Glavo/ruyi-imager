import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.file.RelativePath

plugins {
    application
    id("de.undercouch.download") version "5.7.0"
}

data class FastbootBundle(
    val taskSuffix: String,
    val platformDirectory: String,
    val url: String,
    val archiveEntries: List<String>,
    val executableName: String,
)

data class JlinkJdkBundle(
    val platform: String,
    val url: String,
    val fileName: String,
    val archiveType: JlinkJdkArchiveType,
    val executableName: String,
)

enum class JlinkJdkArchiveType {
    ZIP,
    TAR_GZ,
}

val fastbootBundles = listOf(
    FastbootBundle(
        taskSuffix = "WindowsX8664",
        platformDirectory = "windows-x86_64",
        url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
        archiveEntries = listOf(
            "platform-tools/fastboot.exe",
            "platform-tools/AdbWinApi.dll",
            "platform-tools/AdbWinUsbApi.dll",
        ),
        executableName = "fastboot.exe",
    ),
    FastbootBundle(
        taskSuffix = "MacOSX8664",
        platformDirectory = "macos-x86_64",
        url = "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip",
        archiveEntries = listOf("platform-tools/fastboot"),
        executableName = "fastboot",
    ),
    FastbootBundle(
        taskSuffix = "LinuxX8664",
        platformDirectory = "linux-x86_64",
        url = "https://dl.google.com/android/repository/platform-tools-latest-linux.zip",
        archiveEntries = listOf("platform-tools/fastboot"),
        executableName = "fastboot",
    ),
)

val downloadRetries = providers.gradleProperty("download.retries")
    .map { it.toInt() }
    .orElse(3)
val fastbootDownloadRetries = providers.gradleProperty("fastboot.download.retries")
    .map { it.toInt() }
    .orElse(downloadRetries)
val bundledFastbootDirectory = layout.buildDirectory.dir("bundled-fastboot")
val bundledDdFlasherDirectory = project(":dd-flasher").layout.buildDirectory.dir("bundled-dd-flasher")
val ddFlasherExecutableName =
    if (isWindowsOs(System.getProperty("os.name").lowercase())) "dd-flasher.exe" else "dd-flasher"
val testDdFlasherExecutable = project(":dd-flasher").layout.buildDirectory.file("cargo-target/release/$ddFlasherExecutableName")
val javafxModules = listOf("base", "controls", "graphics")
val javafxModuleNames = javafxModules.map { "javafx.$it" }
val javafxRuntimeAvailable = javafxRuntimePlatform() != null
val applicationJvmArgs = listOf("--enable-native-access=ALL-UNNAMED,javafx.graphics")
val jlinkJdkVersion = providers.gradleProperty("jlink.jdk.version").orElse("25.0.3+11").get()
val jlinkJdkPlatform = providers.gradleProperty("jlink.jdk.platform")
    .orElse(currentJlinkPlatform() ?: error("Unsupported platform for jlink JDK bundle"))
    .get()
val jlinkDdFlasherPlatformDirectory =
    project(":dd-flasher").layout.buildDirectory.dir("bundled-dd-flasher/$jlinkJdkPlatform")
val prepareJlinkBundledDdFlasherTask =
    ":dd-flasher:prepareBundledDdFlasher${platformTaskSuffix(jlinkJdkPlatform)}"
val jlinkRuntimeDirectory = layout.buildDirectory.dir("jlink/$jlinkJdkPlatform/runtime")
val jlinkLaunchersDirectory = layout.buildDirectory.dir("jlink/$jlinkJdkPlatform/launchers")
val jlinkImageDirectory = layout.buildDirectory.dir("jlink/$jlinkJdkPlatform/ruyi-imager")
val jlinkJavafxModuleNames = if (jlinkJdkPlatform == "linux-riscv64") emptyList() else javafxModuleNames
val jlinkDefaultModules = defaultJlinkModules() + jlinkJavafxModuleNames
val jlinkModules = providers.gradleProperty("jlink.modules").orElse(jlinkDefaultModules.joinToString(","))
val jlinkRuntimeIncludesJavafx = providers.provider {
    val moduleSet = jlinkModules.get()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
    jlinkJavafxModuleNames.isNotEmpty() && moduleSet.containsAll(jlinkJavafxModuleNames)
}
val jlinkLauncherJvmArgs = providers.provider {
    if (jlinkRuntimeIncludesJavafx.get()) {
        listOf(
            "--enable-native-access=ALL-UNNAMED,javafx.graphics",
            "--add-modules=${jlinkJavafxModuleNames.joinToString(",")}",
        )
    } else {
        listOf("--enable-native-access=ALL-UNNAMED")
    }
}
val defaultJlinkJdkBundle = libericaJdkBundle(jlinkJdkPlatform, jlinkJdkVersion)
val jlinkJdkUrl = providers.gradleProperty("jlink.jdk.url").orElse(defaultJlinkJdkBundle.url).get()
val jlinkJdkBundle = defaultJlinkJdkBundle.copy(
    url = jlinkJdkUrl,
    fileName = jlinkJdkUrl.substringAfterLast('/'),
    archiveType = jlinkJdkArchiveType(jlinkJdkUrl),
)
val jlinkJdkArchive = layout.buildDirectory.file("downloads/jdks/${jlinkJdkBundle.fileName}")
val jlinkJdkDirectory = layout.buildDirectory.dir("jdks/${jlinkJdkBundle.platform}")
val jlinkJmodsDirectory = jlinkJdkDirectory.map { it.dir("jmods") }
val targetJlinkExecutable = jlinkJdkDirectory.map { it.file("bin/${jlinkJdkBundle.executableName}") }
val java25Launcher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
}
val hostJlinkExecutable = java25Launcher.map {
    val executable = if (isWindowsOs(System.getProperty("os.name").lowercase())) "jlink.exe" else "jlink"
    it.metadata.installationPath.file("bin/$executable")
}

val alibabaPuhuitiFontUrl =
    "https://registry.npmmirror.com/@fontpkg/alibaba-puhuiti-3-0/-/alibaba-puhuiti-3-0-0.0.0.tgz"
val alibabaPuhuitiFontArchive =
    rootProject.layout.buildDirectory.file("downloads/fonts/alibaba-puhuiti-3-0-0.0.0.tgz")
val generatedResourcesDirectory = layout.buildDirectory.dir("generated/resources/main")
val alibabaPuhuitiMediumFont =
    generatedResourcesDirectory.map { it.file("org/glavo/ruyi/imager/fonts/AlibabaPuHuiTi-3-65-Medium.ttf") }

tasks.register<Download>("downloadAlibabaPuhuitiFont") {
    group = "assets"
    description = "Downloads the Alibaba PuHuiTi 3.0 font package."
    src(alibabaPuhuitiFontUrl)
    dest(alibabaPuhuitiFontArchive.get().asFile)
    overwrite(false)
    onlyIfModified(true)
    tempAndMove(true)
    retries(downloadRetries.get())
    outputs.file(alibabaPuhuitiFontArchive)
}

val extractAlibabaPuhuitiMediumFont = tasks.register<Copy>("extractAlibabaPuhuitiMediumFont") {
    group = "assets"
    description = "Extracts the Alibaba PuHuiTi 3.0 Medium TTF font for application resources."
    dependsOn("downloadAlibabaPuhuitiFont")
    from({ tarTree(resources.gzip(alibabaPuhuitiFontArchive)) }) {
        include("package/AlibabaPuHuiTi-3-65-Medium.ttf")
        eachFile {
            relativePath = RelativePath(true, "org", "glavo", "ruyi", "imager", "fonts", name)
        }
        includeEmptyDirs = false
    }
    into(generatedResourcesDirectory)
    outputs.file(alibabaPuhuitiMediumFont)
}

val extractFastbootTasks = fastbootBundles.map { bundle ->
    val archive = layout.buildDirectory.file("downloads/platform-tools-${bundle.platformDirectory}.zip")
    val configuredUrl = providers.gradleProperty("fastboot.${bundle.platformDirectory}.url").orElse(bundle.url)
    val outputFiles = bundle.archiveEntries.map { archiveEntry ->
        val fileName = archiveEntry.substringAfterLast('/')
        bundledFastbootDirectory.map { it.file("${bundle.platformDirectory}/$fileName") }
    }
    val executable = bundledFastbootDirectory.map { it.file("${bundle.platformDirectory}/${bundle.executableName}") }

    val downloadTask = tasks.register<Download>("download${bundle.taskSuffix}Fastboot") {
        group = "distribution"
        description = "Downloads Android Platform Tools for ${bundle.platformDirectory}."
        src(configuredUrl.get())
        dest(archive.get().asFile)
        overwrite(false)
        onlyIfModified(true)
        tempAndMove(true)
        retries(fastbootDownloadRetries.get())
        header("User-Agent", "Ruyi-Imager-Gradle/1.0")
        connectTimeout(30_000)
        readTimeout(120_000)
        outputs.file(archive)
    }

    tasks.register<Copy>("extract${bundle.taskSuffix}Fastboot") {
        dependsOn(downloadTask)
        from({ zipTree(archive.get().asFile) }) {
            include(bundle.archiveEntries)
            eachFile {
                relativePath = RelativePath(true, bundle.platformDirectory, name)
            }
            includeEmptyDirs = false
        }
        into(bundledFastbootDirectory)
        outputs.files(outputFiles)
        doLast {
            if (bundle.executableName == "fastboot") {
                executable.get().asFile.setExecutable(true, false)
            }
        }
    }
}

tasks.register("prepareBundledFastboot") {
    dependsOn(extractFastbootTasks)
}

val downloadJlinkJdk = tasks.register<Download>("downloadJlinkJdk") {
    group = "distribution"
    description = "Downloads the Liberica JDK archive used by jlink packaging."
    src(jlinkJdkBundle.url)
    dest(jlinkJdkArchive.get().asFile)
    overwrite(false)
    onlyIfModified(true)
    tempAndMove(true)
    retries(downloadRetries.get())
    outputs.file(jlinkJdkArchive)
}

tasks.register<Copy>("extractJlinkJdk") {
    group = "distribution"
    description = "Extracts jlink and jmods from the downloaded Liberica JDK archive."
    dependsOn(downloadJlinkJdk)
    from({
        when (jlinkJdkBundle.archiveType) {
            JlinkJdkArchiveType.ZIP -> zipTree(jlinkJdkArchive.get().asFile)
            JlinkJdkArchiveType.TAR_GZ -> tarTree(resources.gzip(jlinkJdkArchive.get().asFile))
        }
    }) {
        include("**/bin/jlink")
        include("**/bin/jlink.exe")
        include("**/jmods/**")
        eachFile {
            val segments = relativePath.segments
            val jmodsIndex = segments.indexOf("jmods")
            val binIndex = segments.indexOf("bin")
            when {
                jmodsIndex >= 0 -> {
                    relativePath = RelativePath(true, *segments.copyOfRange(jmodsIndex, segments.size))
                }
                binIndex >= 0 && segments.lastOrNull() == jlinkJdkBundle.executableName -> {
                    relativePath = RelativePath(true, "bin", jlinkJdkBundle.executableName)
                }
            }
        }
        includeEmptyDirs = false
    }
    into(jlinkJdkDirectory)
    outputs.file(targetJlinkExecutable)
    outputs.file(jlinkJmodsDirectory.map { it.file("java.base.jmod") })
    doLast {
        val executable = targetJlinkExecutable.get().asFile
        if (executable.isFile && jlinkJdkBundle.executableName == "jlink") {
            executable.setExecutable(true, false)
        }
    }
}

dependencies {
    implementation(project(":sdk"))

    compileOnly("org.jetbrains:annotations:26.1.0")
    testCompileOnly("org.jetbrains:annotations:26.1.0")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("info.picocli:picocli:4.7.7")
    implementation("io.github.palexdev:materialfx:21.18.0-alpha")
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.17")

    javafxModules.forEach { module ->
        val runtimeDependency = javafxRuntime(module)
        if (runtimeDependency != null) {
            implementation(runtimeDependency)
        } else {
            javafxCompileOnly(module)?.let {
                compileOnly(it)
                testCompileOnly(it)
            }
        }
    }

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-jdk14:2.0.17")
}

sourceSets {
    main {
        resources.srcDir(generatedResourcesDirectory)
    }
}

application {
    mainClass = "org.glavo.ruyi.imager.Main"
    applicationDefaultJvmArgs = applicationJvmArgs
}

distributions {
    main {
        contents {
            into("tools/fastboot") {
                from(bundledFastbootDirectory)
            }
            into("tools/dd-flasher") {
                from(bundledDdFlasherDirectory)
            }
        }
    }
}

tasks.named("installDist") {
    dependsOn("prepareBundledFastboot")
    dependsOn(":dd-flasher:prepareBundledDdFlasher")
}

tasks.named("distZip") {
    dependsOn("prepareBundledFastboot")
    dependsOn(":dd-flasher:prepareBundledDdFlasher")
}

tasks.named("distTar") {
    dependsOn("prepareBundledFastboot")
    dependsOn(":dd-flasher:prepareBundledDdFlasher")
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED,javafx.graphics")
    dependsOn(":dd-flasher:cargoBuild")
    systemProperty("ruyi.imager.test.ddFlasher.executable", testDdFlasherExecutable.get().asFile.absolutePath)
    if (!javafxRuntimeAvailable) {
        exclude("**/MainWindowJavaFxSmokeTest.class")
    }
}

tasks.processResources {
    dependsOn(extractAlibabaPuhuitiMediumFont)
}

tasks.register<Exec>("jlinkRuntime") {
    group = "distribution"
    description = "Builds a custom Java runtime image for the current platform."
    dependsOn("extractJlinkJdk")
    inputs.property("modules", jlinkModules)
    inputs.property("javafxModules", jlinkJavafxModuleNames.joinToString(","))
    inputs.property("javafxLinked", jlinkRuntimeIncludesJavafx.map { it.toString() })
    inputs.dir(jlinkJmodsDirectory)
    outputs.dir(jlinkRuntimeDirectory)
    doFirst {
        delete(jlinkRuntimeDirectory)
        val executable = hostJlinkExecutable.get().asFile
        val javaBaseJmod = jlinkJmodsDirectory.get().asFile.resolve("java.base.jmod")
        check(executable.isFile) { "Missing host jlink executable: $executable" }
        check(javaBaseJmod.isFile) { "Missing java.base.jmod in downloaded JDK: $javaBaseJmod" }
        if (jlinkRuntimeIncludesJavafx.get()) {
            jlinkJavafxModuleNames.forEach { moduleName ->
                val jmod = jlinkJmodsDirectory.get().asFile.resolve("$moduleName.jmod")
                check(jmod.isFile) {
                    "Missing $moduleName.jmod in downloaded Liberica Full JDK: $jmod"
                }
            }
        }

        setArgs(
            listOf(
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--module-path",
                jlinkJmodsDirectory.get().asFile.absolutePath,
                "--add-modules",
                jlinkModules.get(),
                "--output",
                jlinkRuntimeDirectory.get().asFile.absolutePath,
            ),
        )
    }
    executable = hostJlinkExecutable.get().asFile.absolutePath
}

tasks.register("writeJlinkLaunchers") {
    group = "distribution"
    description = "Writes launch scripts for the jlink application image."
    inputs.property("mainClass", application.mainClass)
    inputs.property("jvmArgs", jlinkLauncherJvmArgs.map { it.joinToString(" ") })
    outputs.dir(jlinkLaunchersDirectory)
    doLast {
        val outputDirectory = jlinkLaunchersDirectory.get().asFile
        val launcherJvmArgs = jlinkLauncherJvmArgs.get().joinToString(" ")
        delete(outputDirectory)
        outputDirectory.mkdirs()

        val unixLauncher = outputDirectory.resolve("ruyi-imager")
        unixLauncher.writeText(
            """
            |#!/bin/sh
            |APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
            |exec "${'$'}APP_HOME/runtime/bin/java" $launcherJvmArgs -cp "${'$'}APP_HOME/lib/*" ${application.mainClass.get()} "$@"
            |
            """.trimMargin(),
        )
        unixLauncher.setExecutable(true, false)

        outputDirectory.resolve("ruyi-imager.bat").writeText(
            """
            |@echo off
            |set "APP_HOME=%~dp0.."
            |"%APP_HOME%\runtime\bin\java.exe" $launcherJvmArgs -cp "%APP_HOME%\lib\*" ${application.mainClass.get()} %*
            |
            """.trimMargin(),
        )
    }
}

tasks.register<Sync>("installJlinkDist") {
    group = "distribution"
    description = "Installs a jlink application image with runtime, libraries, launchers, and bundled tools."
    dependsOn("jar")
    dependsOn("jlinkRuntime")
    dependsOn("writeJlinkLaunchers")
    dependsOn("prepareBundledFastboot")
    dependsOn(prepareJlinkBundledDdFlasherTask)

    into(jlinkImageDirectory)
    from(jlinkRuntimeDirectory) {
        into("runtime")
    }
    from(tasks.named("jar")) {
        into("lib")
    }
    from(configurations.runtimeClasspath) {
        into("lib")
        exclude {
            jlinkRuntimeIncludesJavafx.get() && it.file.name.startsWith("javafx-")
        }
    }
    from(jlinkLaunchersDirectory) {
        into("bin")
    }
    from(bundledFastbootDirectory) {
        into("tools/fastboot")
    }
    from(jlinkDdFlasherPlatformDirectory) {
        into("tools/dd-flasher/$jlinkJdkPlatform")
    }
}

tasks.register<Zip>("jlinkZip") {
    group = "distribution"
    description = "Archives the jlink application image."
    dependsOn("installJlinkDist")
    archiveClassifier = "$jlinkJdkPlatform-jlink"
    from(jlinkImageDirectory) {
        into("ruyi-imager")
    }
}

/// Returns the JavaFX runtime dependency notation for the current build platform.
///
/// @param module JavaFX module name.
/// @return dependency notation, or null when no matching runtime is available from Maven Central.
fun javafxRuntime(module: String): String? =
    javafxRuntimePlatform()?.let { "org.openjfx:javafx-$module:25.0.2:$it" }

/// Returns the JavaFX compile-only dependency notation for the current build platform.
///
/// @param module JavaFX module name.
/// @return dependency notation, or null when no compile fallback is available.
fun javafxCompileOnly(module: String): String? =
    javafxCompilePlatform()?.let { "org.openjfx:javafx-$module:25.0.2:$it" }

/// Returns the JavaFX runtime classifier for the current build platform.
///
/// @return runtime classifier, or null when no matching runtime is available from Maven Central.
fun javafxRuntimePlatform(): String? {
    val osName = System.getProperty("os.name").lowercase()
    val arch = normalizedArch(System.getProperty("os.arch"))
    return when {
        isWindowsOs(osName) && arch == "x86_64" -> "win"
        (osName.contains("mac") || osName.contains("darwin")) && arch == "x86_64" -> "mac"
        (osName.contains("mac") || osName.contains("darwin")) && arch == "aarch64" -> "mac-aarch64"
        osName.contains("linux") && arch == "x86_64" -> "linux"
        osName.contains("linux") && arch == "aarch64" -> "linux-aarch64"
        else -> null
    }
}

/// Returns the JavaFX compile-only classifier for the current build platform.
///
/// @return compile-only classifier, or null when no compile fallback is available.
fun javafxCompilePlatform(): String? {
    val runtimePlatform = javafxRuntimePlatform()
    if (runtimePlatform != null) {
        return runtimePlatform
    }

    val osName = System.getProperty("os.name").lowercase()
    val arch = normalizedArch(System.getProperty("os.arch"))
    // OpenJFX 25.0.2 does not publish a Linux RISC-V runtime classifier, but the Linux API jar can compile the app.
    return if (osName.contains("linux") && arch == "riscv64") "linux" else null
}

/// Resolves a normalized architecture token.
///
/// @param osArch operating system architecture.
/// @return normalized architecture token, or null when unsupported.
fun normalizedArch(osArch: String): String? =
    when (osArch.lowercase()) {
        "amd64", "x86_64", "x86-64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        "riscv64", "risc-v64", "riscv64gc" -> "riscv64"
        else -> null
    }

/// Returns whether an OS name identifies Windows.
///
/// @param osName normalized operating system name.
/// @return whether the OS is Windows.
fun isWindowsOs(osName: String): Boolean =
    osName.startsWith("windows")

/// Returns the jlink target platform token for the current build host.
///
/// @return current jlink platform token, or null when unsupported.
fun currentJlinkPlatform(): String? {
    val osName = System.getProperty("os.name").lowercase()
    val arch = normalizedArch(System.getProperty("os.arch"))
    return when {
        isWindowsOs(osName) && arch == "x86_64" -> "windows-x86_64"
        isWindowsOs(osName) && arch == "aarch64" -> "windows-aarch64"
        (osName.contains("mac") || osName.contains("darwin")) && arch == "x86_64" -> "macos-x86_64"
        (osName.contains("mac") || osName.contains("darwin")) && arch == "aarch64" -> "macos-aarch64"
        osName.contains("linux") && arch == "x86_64" -> "linux-x86_64"
        osName.contains("linux") && arch == "aarch64" -> "linux-aarch64"
        osName.contains("linux") && arch == "riscv64" -> "linux-riscv64"
        else -> null
    }
}

/// Creates the default Liberica JDK bundle descriptor for a jlink JDK platform.
///
/// @param platform jlink JDK platform token.
/// @param version Liberica JDK version string.
/// @return Liberica JDK bundle descriptor.
fun libericaJdkBundle(platform: String, version: String): JlinkJdkBundle {
    val platformArchive = when (platform) {
        "windows-x86_64" -> "windows-amd64.zip"
        "windows-aarch64" -> "windows-aarch64.zip"
        "macos-x86_64" -> "macos-amd64.tar.gz"
        "macos-aarch64" -> "macos-aarch64.tar.gz"
        "linux-x86_64" -> "linux-amd64.tar.gz"
        "linux-aarch64" -> "linux-aarch64.tar.gz"
        "linux-riscv64" -> "linux-riscv64.tar.gz"
        else -> error("Unsupported jlink target platform: $platform")
    }
    val archiveExtension = if (platformArchive.endsWith(".tar.gz")) ".tar.gz" else ".zip"
    val platformName = platformArchive.removeSuffix(archiveExtension)
    val bundleSuffix = if (platform == "linux-riscv64") "" else "-full"
    val fileName = "bellsoft-jdk$version-$platformName$bundleSuffix$archiveExtension"
    val executableName = if (platform.startsWith("windows-")) "jlink.exe" else "jlink"
    return JlinkJdkBundle(
        platform = platform,
        url = "https://download.bell-sw.com/java/$version/$fileName",
        fileName = fileName,
        archiveType = jlinkJdkArchiveType(fileName),
        executableName = executableName,
    )
}

/// Determines the archive type from a JDK archive URL or file name.
///
/// @param archiveName JDK archive URL or file name.
/// @return archive type.
fun jlinkJdkArchiveType(archiveName: String): JlinkJdkArchiveType =
    when {
        archiveName.endsWith(".zip", ignoreCase = true) -> JlinkJdkArchiveType.ZIP
        archiveName.endsWith(".tar.gz", ignoreCase = true) -> JlinkJdkArchiveType.TAR_GZ
        else -> error("Unsupported JDK archive type: $archiveName")
    }

/// Converts a platform directory name into a Gradle task suffix.
///
/// @param platformDirectory distribution platform directory.
/// @return Gradle task suffix.
fun platformTaskSuffix(platformDirectory: String): String =
    platformDirectory.split('-', '_').joinToString("") { token ->
        token.replaceFirstChar { it.uppercaseChar() }
    }

/// Returns the default Java modules included in the jlink runtime image.
///
/// @return default module names.
fun defaultJlinkModules(): List<String> =
    listOf(
        "java.base",
        "java.compiler",
        "java.datatransfer",
        "java.desktop",
        "java.logging",
        "java.management",
        "java.naming",
        "java.net.http",
        "java.prefs",
        "java.scripting",
        "java.security.jgss",
        "java.sql",
        "java.xml",
        "jdk.charsets",
        "jdk.crypto.ec",
        "jdk.localedata",
        "jdk.unsupported",
    )
