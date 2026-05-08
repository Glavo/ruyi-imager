import org.gradle.api.file.RelativePath
import java.net.URI

plugins {
    application
}

data class FastbootBundle(
    val taskSuffix: String,
    val platformDirectory: String,
    val url: String,
    val archiveEntries: List<String>,
    val executableName: String,
)

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

val bundledFastbootDirectory = layout.buildDirectory.dir("bundled-fastboot")
val bundledDdFlasherDirectory = project(":dd-flasher").layout.buildDirectory.dir("bundled-dd-flasher")
val ddFlasherExecutableName =
    if (isWindowsOs(System.getProperty("os.name").lowercase())) "dd-flasher.exe" else "dd-flasher"
val testDdFlasherExecutable = project(":dd-flasher").layout.buildDirectory.file("cargo-target/release/$ddFlasherExecutableName")
val javafxModules = listOf("base", "controls", "graphics")
val javafxRuntimeAvailable = javafxRuntimePlatform() != null
val applicationJvmArgs = listOf("--enable-native-access=ALL-UNNAMED,javafx.graphics")
val jlinkJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
val jlinkRuntimeDirectory = layout.buildDirectory.dir("jlink/runtime")
val jlinkLaunchersDirectory = layout.buildDirectory.dir("jlink/launchers")
val jlinkImageDirectory = layout.buildDirectory.dir("jlink/ruyi-imager")
val jlinkModules = providers.gradleProperty("jlink.modules").orElse(defaultJlinkModules().joinToString(","))
val java25Launcher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
}
val jlinkExecutable = java25Launcher.map {
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

tasks.register("downloadAlibabaPuhuitiFont") {
    group = "assets"
    description = "Downloads the Alibaba PuHuiTi 3.0 font package."
    outputs.file(alibabaPuhuitiFontArchive)
    doLast {
        val target = alibabaPuhuitiFontArchive.get().asFile
        target.parentFile.mkdirs()
        URI(alibabaPuhuitiFontUrl).toURL().openStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
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
    val outputFiles = bundle.archiveEntries.map { archiveEntry ->
        val fileName = archiveEntry.substringAfterLast('/')
        bundledFastbootDirectory.map { it.file("${bundle.platformDirectory}/$fileName") }
    }
    val executable = bundledFastbootDirectory.map { it.file("${bundle.platformDirectory}/${bundle.executableName}") }

    val downloadTask = tasks.register("download${bundle.taskSuffix}Fastboot") {
        outputs.file(archive)
        doLast {
            val target = archive.get().asFile
            target.parentFile.mkdirs()
            URI(bundle.url).toURL().openStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
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
    inputs.property("modules", jlinkModules)
    outputs.dir(jlinkRuntimeDirectory)
    doFirst {
        delete(jlinkRuntimeDirectory)
    }
    executable = jlinkExecutable.get().asFile.absolutePath
    args(
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
        "--add-modules",
        jlinkModules.get(),
        "--output",
        jlinkRuntimeDirectory.get().asFile.absolutePath,
    )
}

tasks.register("writeJlinkLaunchers") {
    group = "distribution"
    description = "Writes launch scripts for the jlink application image."
    inputs.property("mainClass", application.mainClass)
    inputs.property("jvmArgs", jlinkJvmArgs.joinToString(" "))
    outputs.dir(jlinkLaunchersDirectory)
    doLast {
        val outputDirectory = jlinkLaunchersDirectory.get().asFile
        delete(outputDirectory)
        outputDirectory.mkdirs()

        val unixLauncher = outputDirectory.resolve("ruyi-imager")
        unixLauncher.writeText(
            """
            |#!/bin/sh
            |APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
            |exec "${'$'}APP_HOME/runtime/bin/java" ${jlinkJvmArgs.joinToString(" ")} -cp "${'$'}APP_HOME/lib/*" ${application.mainClass.get()} "$@"
            |
            """.trimMargin(),
        )
        unixLauncher.setExecutable(true, false)

        outputDirectory.resolve("ruyi-imager.bat").writeText(
            """
            |@echo off
            |set "APP_HOME=%~dp0.."
            |"%APP_HOME%\runtime\bin\java.exe" ${jlinkJvmArgs.joinToString(" ")} -cp "%APP_HOME%\lib\*" ${application.mainClass.get()} %*
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
    dependsOn(":dd-flasher:prepareBundledDdFlasher")

    into(jlinkImageDirectory)
    from(jlinkRuntimeDirectory) {
        into("runtime")
    }
    from(tasks.named("jar")) {
        into("lib")
    }
    from(configurations.runtimeClasspath) {
        into("lib")
    }
    from(jlinkLaunchersDirectory) {
        into("bin")
    }
    from(bundledFastbootDirectory) {
        into("tools/fastboot")
    }
    from(bundledDdFlasherDirectory) {
        into("tools/dd-flasher")
    }
}

tasks.register<Zip>("jlinkZip") {
    group = "distribution"
    description = "Archives the jlink application image."
    dependsOn("installJlinkDist")
    archiveClassifier = "jlink"
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
