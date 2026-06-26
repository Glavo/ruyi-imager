import de.undercouch.gradle.tasks.download.Download
import de.undercouch.gradle.tasks.download.Verify
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.gradle.api.file.RelativePath
import org.gradle.api.tasks.bundling.Compression

plugins {
    application
    id("de.undercouch.download") version "5.7.0"
}

data class FastbootBundle(
    val taskSuffix: String,
    val platformDirectory: String,
    val archiveFileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
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

val fastbootPlatformToolsVersion = "37.0.0"
val fastbootBundles = listOf(
    FastbootBundle(
        taskSuffix = "WindowsX8664",
        platformDirectory = "windows-x86_64",
        archiveFileName = "platform-tools_r$fastbootPlatformToolsVersion-win.zip",
        url = "https://dl.google.com/android/repository/platform-tools_r$fastbootPlatformToolsVersion-win.zip",
        sha256 = "4fe305812db074cea32903a489d061eb4454cbc90a49e8fea677f4b7af764918",
        sizeBytes = 8_092_164L,
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
        archiveFileName = "platform-tools_r$fastbootPlatformToolsVersion-darwin.zip",
        url = "https://dl.google.com/android/repository/platform-tools_r$fastbootPlatformToolsVersion-darwin.zip",
        sha256 = "094a1395683c509fd4d48667da0d8b5ef4d42b2abfcd29f2e8149e2f989357c7",
        sizeBytes = 16_442_240L,
        archiveEntries = listOf("platform-tools/fastboot"),
        executableName = "fastboot",
    ),
    FastbootBundle(
        taskSuffix = "LinuxX8664",
        platformDirectory = "linux-x86_64",
        archiveFileName = "platform-tools_r$fastbootPlatformToolsVersion-linux.zip",
        url = "https://dl.google.com/android/repository/platform-tools_r$fastbootPlatformToolsVersion-linux.zip",
        sha256 = "198ae156ab285fa555987219af237b31102fefe8b9d2bc274708a8d4f2865a07",
        sizeBytes = 9_167_924L,
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
val bundledDDFlasherDirectory = project(":dd-flasher").layout.buildDirectory.dir("bundled-dd-flasher")
val powerShellScriptsDirectory =
    project(":sdk").layout.projectDirectory.dir("src/main/resources/org/glavo/ruyi/imager/core/powershell")
val ddFlasherExecutableName =
    if (isWindowsOs(System.getProperty("os.name").lowercase())) "dd-flasher.exe" else "dd-flasher"
val testDDFlasherExecutable = project(":dd-flasher").layout.buildDirectory.file("cargo-target/release/$ddFlasherExecutableName")
val javafxModules = listOf("base", "controls", "graphics")
val javafxModuleNames = javafxModules.map { "javafx.$it" }
val javafxRuntimeAvailable = javafxRuntimePlatform() != null
val applicationJvmArgs = listOf("--enable-native-access=ALL-UNNAMED,javafx.graphics")
val jlinkJdkVersion = providers.gradleProperty("jlink.jdk.version").orElse("25.0.3+11").get()
val jlinkJdkPlatform = providers.gradleProperty("jlink.jdk.platform")
    .orElse(currentJlinkPlatform() ?: error("Unsupported platform for jlink JDK bundle"))
    .get()
val jlinkDDFlasherPlatformDirectory =
    project(":dd-flasher").layout.buildDirectory.dir("bundled-dd-flasher/$jlinkJdkPlatform")
val prepareJlinkBundledDDFlasherTask =
    ":dd-flasher:prepareBundledDDFlasher${platformTaskSuffix(jlinkJdkPlatform)}"
val jlinkNativeLauncherDirectory =
    project(":launcher").layout.buildDirectory.dir("bundled-launcher/$jlinkJdkPlatform")
val prepareJlinkNativeLauncherTask =
    if (jlinkJdkPlatform.startsWith("windows-")) {
        ":launcher:prepareBundledLauncher${platformTaskSuffix(jlinkJdkPlatform)}"
    } else {
        null
    }
val jlinkFastbootBundle = fastbootBundles.firstOrNull { it.platformDirectory == jlinkJdkPlatform }
val runFastbootBundle = currentJlinkPlatform()?.let { platform ->
    fastbootBundles.firstOrNull { it.platformDirectory == platform }
}
val jlinkRuntimeDirectory = layout.buildDirectory.dir("jlink/$jlinkJdkPlatform/runtime")
val jlinkLaunchersDirectory = layout.buildDirectory.dir("jlink/$jlinkJdkPlatform/launchers")
val jlinkImageDirectory = layout.buildDirectory.dir("jlink/$jlinkJdkPlatform/ruyi-imager")
val jlinkDebArchitecture = debianArchitecture(jlinkJdkPlatform)
val jlinkDebVersion = debianVersion(project.version.toString())
val jlinkDebPackageName = providers.gradleProperty("jlink.deb.packageName").orElse("ruyi-imager")
val jlinkDebMaintainer = providers.gradleProperty("jlink.deb.maintainer").orElse("Glavo <zjx001202@gmail.com>")
val jlinkDebHomepage = providers.gradleProperty("jlink.deb.homepage").orElse("https://github.com/Glavo/ruyi-imager")
val jlinkDebDirectory = layout.buildDirectory.dir("jlink/$jlinkJdkPlatform/deb")
val jlinkDebMetadataDirectory = jlinkDebDirectory.map { it.dir("metadata") }
val jlinkDebDataDirectory = jlinkDebDirectory.map { it.dir("data") }
val jlinkDebControlDirectory = jlinkDebDirectory.map { it.dir("control") }
val jlinkDebControlArchive = jlinkDebDirectory.map { it.file("control.tar.gz") }
val jlinkDebDataArchive = jlinkDebDirectory.map { it.file("data.tar.gz") }
val jlinkDebOutputFile = layout.buildDirectory.file(
    "distributions/${jlinkDebPackageName.get()}_${jlinkDebVersion}_${jlinkDebArchitecture ?: "unsupported"}.deb",
)
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
    val archive = layout.buildDirectory.file("downloads/${bundle.archiveFileName}")
    val configuredUrl = providers.gradleProperty("fastboot.${bundle.platformDirectory}.url").orElse(bundle.url)
    val configuredSha256 = providers.gradleProperty("fastboot.${bundle.platformDirectory}.sha256").orElse(bundle.sha256)
    val configuredSizeBytes = providers.gradleProperty("fastboot.${bundle.platformDirectory}.sizeBytes")
        .map { it.toLong() }
        .orElse(bundle.sizeBytes)
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

    val verifyTask = tasks.register<Verify>("verify${bundle.taskSuffix}Fastboot") {
        group = "distribution"
        description = "Verifies Android Platform Tools for ${bundle.platformDirectory}."
        dependsOn(downloadTask)
        src(archive.get().asFile)
        algorithm("SHA-256")
        checksum(configuredSha256.get())
        inputs.property("expectedSizeBytes", configuredSizeBytes)
        doLast {
            val expectedSizeBytes = configuredSizeBytes.get()
            val actualSizeBytes = archive.get().asFile.length()
            check(actualSizeBytes == expectedSizeBytes) {
                "Expected ${bundle.archiveFileName} to be $expectedSizeBytes bytes, but was $actualSizeBytes bytes"
            }
        }
    }

    tasks.register<Copy>("extract${bundle.taskSuffix}Fastboot") {
        dependsOn(verifyTask)
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
        resources.srcDir(rootProject.layout.projectDirectory.dir("resources"))
    }
}

application {
    mainClass = "org.glavo.ruyi.imager.Main"
    applicationDefaultJvmArgs = applicationJvmArgs
}

tasks.named<JavaExec>("run") {
    dependsOn(":dd-flasher:cargoBuild")
    doFirst {
        systemProperty("ruyi.imager.ddFlasher.executable", testDDFlasherExecutable.get().asFile.absolutePath)
        systemProperty("ruyi.imager.powershell.scripts", powerShellScriptsDirectory.asFile.absolutePath)
    }
    runFastbootBundle?.let { bundle ->
        val executable = bundledFastbootDirectory.map {
            it.file("${bundle.platformDirectory}/${bundle.executableName}")
        }
        dependsOn("extract${bundle.taskSuffix}Fastboot")
        doFirst {
            systemProperty("ruyi.imager.fastboot.executable", executable.get().asFile.absolutePath)
        }
    }
}

distributions {
    main {
        contents {
            into("tools/fastboot") {
                from(bundledFastbootDirectory)
            }
            into("tools/dd-flasher") {
                from(bundledDDFlasherDirectory)
            }
            into("tools/powershell") {
                from(powerShellScriptsDirectory) {
                    include("*.ps1")
                }
            }
        }
    }
}

tasks.named("installDist") {
    dependsOn("prepareBundledFastboot")
    dependsOn(":dd-flasher:prepareBundledDDFlasher")
}

tasks.named("distZip") {
    dependsOn("prepareBundledFastboot")
    dependsOn(":dd-flasher:prepareBundledDDFlasher")
}

tasks.named("distTar") {
    dependsOn("prepareBundledFastboot")
    dependsOn(":dd-flasher:prepareBundledDDFlasher")
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED,javafx.graphics")
    dependsOn(":dd-flasher:cargoBuild")
    systemProperty("ruyi.imager.test.ddFlasher.executable", testDDFlasherExecutable.get().asFile.absolutePath)
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

        val unixLauncherText =
            """
            |#!/bin/sh
            |APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
            |exec "${'$'}APP_HOME/runtime/bin/java" $launcherJvmArgs -cp "${'$'}APP_HOME/lib/*" ${application.mainClass.get()} "$@"
            |
            """.trimMargin()

        val unixLauncher = outputDirectory.resolve("ruyi-imager")
        unixLauncher.writeText(unixLauncherText)
        unixLauncher.setExecutable(true, false)

        val unixCliLauncher = outputDirectory.resolve("ruyi-imager-cli")
        unixCliLauncher.writeText(unixLauncherText)
        unixCliLauncher.setExecutable(true, false)

        outputDirectory.resolve("ruyi-imager-cli.bat").writeText(
            """
            |@echo off
            |set "APP_HOME=%~dp0.."
            |"%APP_HOME%\runtime\bin\java.exe" $launcherJvmArgs -cp "%APP_HOME%\lib\*" ${application.mainClass.get()} %*
            |
            """.trimMargin(),
        )

        outputDirectory.resolve("ruyi-imager.jvmargs").writeText(
            jlinkLauncherJvmArgs.get().joinToString(System.lineSeparator(), postfix = System.lineSeparator()),
        )
    }
}

tasks.register<Sync>("installJlinkDist") {
    group = "distribution"
    description = "Installs a jlink application image with runtime, libraries, launchers, and bundled tools."
    dependsOn("jar")
    dependsOn("jlinkRuntime")
    dependsOn("writeJlinkLaunchers")
    jlinkFastbootBundle?.let { dependsOn("extract${it.taskSuffix}Fastboot") }
    dependsOn(prepareJlinkBundledDDFlasherTask)
    prepareJlinkNativeLauncherTask?.let { dependsOn(it) }

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
    prepareJlinkNativeLauncherTask?.let {
        from(jlinkNativeLauncherDirectory) {
            into("bin")
        }
    }
    jlinkFastbootBundle?.let { bundle ->
        from(bundledFastbootDirectory.map { it.dir(bundle.platformDirectory) }) {
            into("tools/fastboot/${bundle.platformDirectory}")
        }
    }
    from(jlinkDDFlasherPlatformDirectory) {
        into("tools/dd-flasher/$jlinkJdkPlatform")
    }
    from(powerShellScriptsDirectory) {
        into("tools/powershell")
        include("*.ps1")
    }
}

val writeJlinkDebMetadata = tasks.register("writeJlinkDebMetadata") {
    group = "distribution"
    description = "Writes Debian package launcher and desktop metadata."
    outputs.dir(jlinkDebMetadataDirectory)
    doFirst {
        requireDebianArchitecture(jlinkJdkPlatform)
    }
    doLast {
        val outputDirectory = jlinkDebMetadataDirectory.get().asFile
        delete(outputDirectory)

        val binDirectory = outputDirectory.resolve("usr/bin")
        val applicationsDirectory = outputDirectory.resolve("usr/share/applications")
        binDirectory.mkdirs()
        applicationsDirectory.mkdirs()

        binDirectory.resolve("ruyi-imager").writeText(
            """
            |#!/bin/sh
            |exec /opt/ruyi-imager/bin/ruyi-imager "$@"
            |
            """.trimMargin(),
        )
        binDirectory.resolve("ruyi-imager-cli").writeText(
            """
            |#!/bin/sh
            |exec /opt/ruyi-imager/bin/ruyi-imager-cli "$@"
            |
            """.trimMargin(),
        )
        applicationsDirectory.resolve("ruyi-imager.desktop").writeText(
            """
            |[Desktop Entry]
            |Type=Application
            |Name=Ruyi Imager
            |Comment=Flash Ruyi SDK and local images to removable devices
            |Exec=ruyi-imager
            |Icon=ruyi-imager
            |Terminal=false
            |StartupNotify=true
            |Categories=Utility;System;
            |Keywords=ruyi;imager;flash;sd-card;
            |
            """.trimMargin(),
        )
    }
}

val prepareJlinkDebData = tasks.register<Sync>("prepareJlinkDebData") {
    group = "distribution"
    description = "Stages the Debian package data tree."
    dependsOn("installJlinkDist")
    dependsOn(writeJlinkDebMetadata)
    doFirst {
        requireDebianArchitecture(jlinkJdkPlatform)
    }
    into(jlinkDebDataDirectory)
    from(jlinkImageDirectory) {
        into("opt/ruyi-imager")
    }
    from(jlinkDebMetadataDirectory)
    from(rootProject.layout.projectDirectory.file("resources/ruyi-logo-256.png")) {
        into("usr/share/icons/hicolor/256x256/apps")
        rename { "ruyi-imager.png" }
    }
}

val writeJlinkDebControl = tasks.register("writeJlinkDebControl") {
    group = "distribution"
    description = "Writes Debian package control metadata."
    dependsOn(prepareJlinkDebData)
    inputs.dir(jlinkDebDataDirectory)
    inputs.property("packageName", jlinkDebPackageName)
    inputs.property("version", jlinkDebVersion)
    inputs.property("architecture", jlinkDebArchitecture ?: "")
    inputs.property("maintainer", jlinkDebMaintainer)
    inputs.property("homepage", jlinkDebHomepage)
    outputs.dir(jlinkDebControlDirectory)
    doFirst {
        requireDebianArchitecture(jlinkJdkPlatform)
    }
    doLast {
        val outputDirectory = jlinkDebControlDirectory.get().asFile
        delete(outputDirectory)
        outputDirectory.mkdirs()

        val dataDirectory = jlinkDebDataDirectory.get().asFile
        val installedSize = installedSizeKilobytes(dataDirectory)
        outputDirectory.resolve("control").writeText(
            """
            |Package: ${jlinkDebPackageName.get()}
            |Version: $jlinkDebVersion
            |Section: utils
            |Priority: optional
            |Architecture: ${requireDebianArchitecture(jlinkJdkPlatform)}
            |Installed-Size: $installedSize
            |Maintainer: ${jlinkDebMaintainer.get()}
            |Description: Ruyi Imager
            | Ruyi Imager flashes Ruyi SDK catalog and local images to removable devices.
            |Homepage: ${jlinkDebHomepage.get()}
            |
            """.trimMargin(),
        )
    }
}

val jlinkDebControlTar = tasks.register<Tar>("jlinkDebControlTar") {
    group = "distribution"
    description = "Archives Debian package control metadata."
    dependsOn(writeJlinkDebControl)
    archiveFileName = "control.tar.gz"
    destinationDirectory = jlinkDebDirectory
    compression = Compression.GZIP
    from(jlinkDebControlDirectory) {
        eachFile {
            permissions {
                unix(if (isDirectory) "755" else "644")
            }
        }
    }
}

val jlinkDebDataTar = tasks.register<Tar>("jlinkDebDataTar") {
    group = "distribution"
    description = "Archives Debian package data files."
    dependsOn(prepareJlinkDebData)
    archiveFileName = "data.tar.gz"
    destinationDirectory = jlinkDebDirectory
    compression = Compression.GZIP
    from(jlinkDebDataDirectory) {
        eachFile {
            permissions {
                unix(if (isDirectory || jlinkDebExecutablePath(path, jlinkJdkPlatform)) "755" else "644")
            }
        }
    }
}

tasks.register("jlinkDeb") {
    group = "distribution"
    description = "Builds a Debian package from the jlink application image."
    dependsOn(jlinkDebControlTar)
    dependsOn(jlinkDebDataTar)
    inputs.file(jlinkDebControlArchive)
    inputs.file(jlinkDebDataArchive)
    outputs.file(jlinkDebOutputFile)
    doFirst {
        requireDebianArchitecture(jlinkJdkPlatform)
    }
    doLast {
        writeDebianArchive(
            jlinkDebOutputFile.get().asFile,
            jlinkDebControlArchive.get().asFile,
            jlinkDebDataArchive.get().asFile,
        )
    }
}

val jlinkArchiveTask = if (jlinkJdkPlatform.startsWith("windows-")) {
    tasks.register<Zip>("jlinkArchive") {
        group = "distribution"
        description = "Archives the jlink application image."
        dependsOn("installJlinkDist")
        archiveBaseName = "ruyi-imager"
        archiveClassifier = jlinkJdkPlatform
        from(jlinkImageDirectory) {
            into("ruyi-imager")
        }
    }
} else {
    tasks.register<Tar>("jlinkArchive") {
        group = "distribution"
        description = "Archives the jlink application image."
        dependsOn("installJlinkDist")
        archiveBaseName = "ruyi-imager"
        archiveClassifier = jlinkJdkPlatform
        archiveExtension = "tar.gz"
        compression = Compression.GZIP
        from(jlinkImageDirectory) {
            into("ruyi-imager")
            eachFile {
                permissions {
                    unix(if (jlinkUnixExecutableArchivePath(path, jlinkJdkPlatform)) "755" else "644")
                }
            }
        }
    }
}

tasks.register("jlinkZip") {
    group = "distribution"
    description = "Compatibility alias for jlinkArchive."
    dependsOn(jlinkArchiveTask)
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

/// Returns the Debian architecture name for a jlink target platform.
///
/// @param platform jlink target platform token.
/// @return Debian architecture name, or null when the platform cannot be packaged as Debian.
fun debianArchitecture(platform: String): String? =
    when (platform) {
        "linux-x86_64" -> "amd64"
        "linux-aarch64" -> "arm64"
        "linux-riscv64" -> "riscv64"
        else -> null
    }

/// Returns the Debian architecture name or fails with a clear packaging error.
///
/// @param platform jlink target platform token.
/// @return Debian architecture name.
fun requireDebianArchitecture(platform: String): String =
    debianArchitecture(platform)
        ?: error("Debian packaging is supported only for Linux jlink platforms, but was requested for $platform")

/// Converts a Gradle project version into a Debian-compatible package version.
///
/// @param version Gradle project version.
/// @return Debian-compatible version string.
fun debianVersion(version: String): String {
    val text = version.trim().ifEmpty { "0" }.replace("-SNAPSHOT", "~SNAPSHOT")
    val builder = StringBuilder(text.length + 2)
    for (char in text) {
        if (char.isLetterOrDigit() || char == '.' || char == '+' || char == '-' || char == ':' || char == '~') {
            builder.append(char)
        } else {
            builder.append('+')
        }
    }
    if (!builder.first().isDigit()) {
        builder.insert(0, "0~")
    }
    return builder.toString()
}

/// Returns whether a jlink archive path must be executable on Unix platforms.
///
/// @param path archive entry path.
/// @param platform jlink target platform token.
/// @return whether the entry must be executable.
fun jlinkUnixExecutableArchivePath(path: String, platform: String): Boolean {
    val relativePath = path.replace('\\', '/').removePrefix("ruyi-imager/")
    return relativePath == "bin/ruyi-imager"
        || relativePath == "bin/ruyi-imager-cli"
        || relativePath.startsWith("runtime/bin/")
        || relativePath == "runtime/lib/jspawnhelper"
        || relativePath == "runtime/lib/jexec"
        || relativePath == "tools/fastboot/$platform/fastboot"
        || relativePath == "tools/dd-flasher/$platform/dd-flasher"
}

/// Returns whether a Debian package data path must be executable.
///
/// @param path Debian data archive entry path.
/// @param platform jlink target platform token.
/// @return whether the entry must be executable.
fun jlinkDebExecutablePath(path: String, platform: String): Boolean {
    val relativePath = path.replace('\\', '/')
    if (relativePath == "usr/bin/ruyi-imager" || relativePath == "usr/bin/ruyi-imager-cli") {
        return true
    }
    return relativePath.startsWith("opt/ruyi-imager/")
        && jlinkUnixExecutableArchivePath(relativePath.removePrefix("opt/ruyi-imager/"), platform)
}

/// Computes the Debian `Installed-Size` field for a staged data directory.
///
/// @param directory staged Debian data directory.
/// @return installed size in KiB.
fun installedSizeKilobytes(directory: File): Long {
    val bytes = directory.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }
    return maxOf((bytes + 1023L) / 1024L, 1L)
}

/// Writes the final Debian ar container.
///
/// @param outputFile final `.deb` file.
/// @param controlArchive `control.tar.gz` member.
/// @param dataArchive `data.tar.gz` member.
fun writeDebianArchive(outputFile: File, controlArchive: File, dataArchive: File) {
    outputFile.parentFile.mkdirs()
    outputFile.outputStream().use { output ->
        output.write("!<arch>\n".toByteArray(StandardCharsets.US_ASCII))
        writeDebianArEntry(output, "debian-binary", "2.0\n".toByteArray(StandardCharsets.US_ASCII))
        writeDebianArEntry(output, "control.tar.gz", controlArchive.readBytes())
        writeDebianArEntry(output, "data.tar.gz", dataArchive.readBytes())
    }
}

/// Writes one Debian ar member.
///
/// @param output destination ar stream.
/// @param name member name.
/// @param content member bytes.
fun writeDebianArEntry(output: OutputStream, name: String, content: ByteArray) {
    val entryName = "$name/"
    require(entryName.length <= 16) { "Debian ar member name is too long: $name" }
    val header = String.format(
        Locale.ROOT,
        "%-16s%-12s%-6s%-6s%-8s%-10s`\n",
        entryName,
        "0",
        "0",
        "0",
        "100644",
        content.size.toString(),
    )
    check(header.toByteArray(StandardCharsets.US_ASCII).size == 60) { "Invalid Debian ar header for $name" }
    output.write(header.toByteArray(StandardCharsets.US_ASCII))
    output.write(content)
    if (content.size % 2 != 0) {
        output.write('\n'.code)
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
