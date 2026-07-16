import de.undercouch.gradle.tasks.download.Download
import org.glavo.ruyi.imager.gradle.CreateDeb
import org.glavo.ruyi.imager.gradle.CreateJlinkRuntime
import org.glavo.ruyi.imager.gradle.ExtractZipEntries
import org.glavo.ruyi.imager.gradle.JlinkPackaging.debianVersion
import org.glavo.ruyi.imager.gradle.JlinkPackaging.jlinkDebExecutablePath
import org.glavo.ruyi.imager.gradle.JlinkPackaging.jlinkUnixExecutableArchivePath
import org.glavo.ruyi.imager.gradle.JlinkPackaging.platformTaskSuffix
import org.glavo.ruyi.imager.gradle.JlinkPackaging.requireDebianArchitecture
import org.glavo.ruyi.imager.gradle.RunWixBundleBuild
import org.glavo.ruyi.imager.gradle.RunWixBuild
import org.glavo.ruyi.imager.gradle.VerifyFile
import org.glavo.ruyi.imager.gradle.WixPackaging.msiVersion
import org.glavo.ruyi.imager.gradle.WixPackaging.requireWixArchitecture
import org.glavo.ruyi.imager.gradle.WriteDebControl
import org.glavo.ruyi.imager.gradle.WriteJlinkDebMetadata
import org.glavo.ruyi.imager.gradle.WriteJlinkLaunchers
import org.glavo.ruyi.imager.gradle.WriteWixBundleSource
import org.glavo.ruyi.imager.gradle.WriteWixSource
import org.gradle.api.file.RelativePath
import org.gradle.api.tasks.WriteProperties
import org.gradle.api.tasks.bundling.Compression
import java.net.URI

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
    val sha256: String,
    val sizeBytes: Long,
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
    "distributions/ruyi-imager-${project.version}-$jlinkJdkPlatform.deb",
)
val jlinkMsiProductName = providers.gradleProperty("jlink.msi.productName").orElse("Ruyi Imager")
val jlinkMsiManufacturer = providers.gradleProperty("jlink.msi.manufacturer").orElse("Glavo")
val jlinkMsiProductVersion =
    providers.gradleProperty("jlink.msi.productVersion").orElse(msiVersion(project.version.toString()))
val jlinkMsiUpgradeCode =
    providers.gradleProperty("jlink.msi.upgradeCode").orElse("9D6D03B2-48F4-4F44-B8F6-7F6E3E4B29A1")
val jlinkMsiInstallScope = providers.gradleProperty("jlink.msi.installScope").orElse("perUser")
val jlinkMsiWixExecutable = providers.gradleProperty("wix.executable").orElse("wix")
val jlinkMsiArchitecture = providers.provider { requireWixArchitecture(jlinkJdkPlatform) }
val jlinkMsiDirectory = layout.buildDirectory.dir("jlink/$jlinkJdkPlatform/msi")
val jlinkMsiSourceFile = jlinkMsiDirectory.map { it.file("ruyi-imager.wxs") }
val jlinkMsiOutputFile = layout.buildDirectory.file(
    "distributions/ruyi-imager-${project.version}-$jlinkJdkPlatform.msi",
)
val jlinkSetupUpgradeCode =
    providers.gradleProperty("jlink.setup.upgradeCode").orElse("AA010C2D-88C0-4B7E-8B29-624FA17592B9")
val jlinkSetupDirectory = layout.buildDirectory.dir("jlink/$jlinkJdkPlatform/setup")
val jlinkSetupSourceFile = jlinkSetupDirectory.map { it.file("ruyi-imager-setup.wxs") }
val jlinkSetupOutputFile = layout.buildDirectory.file(
    "distributions/ruyi-imager-${project.version}-$jlinkJdkPlatform-setup.exe",
)
val jlinkSetupLocalizationDirectory = rootProject.layout.projectDirectory.dir("resources/wix/setup")
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
val customJlinkJdkUrl = providers.gradleProperty("jlink.jdk.url").orNull
val jlinkJdkBundle = if (customJlinkJdkUrl == null) {
    libericaJdkBundle(jlinkJdkPlatform, jlinkJdkVersion)
} else {
    val customJlinkJdkSha256 = providers.gradleProperty("jlink.jdk.sha256").orNull
        ?.takeIf { it.isNotBlank() }
        ?: error("jlink.jdk.sha256 is required when jlink.jdk.url is configured")
    val customJlinkJdkSizeBytes = providers.gradleProperty("jlink.jdk.sizeBytes").orNull
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?: error("jlink.jdk.sizeBytes must be a positive integer when jlink.jdk.url is configured")
    val customJlinkJdkFileName = URI.create(customJlinkJdkUrl).path.substringAfterLast('/')
    require(customJlinkJdkFileName.isNotBlank()) {
        "jlink.jdk.url must identify a JDK archive file"
    }
    JlinkJdkBundle(
        platform = jlinkJdkPlatform,
        url = customJlinkJdkUrl,
        fileName = customJlinkJdkFileName,
        sha256 = customJlinkJdkSha256,
        sizeBytes = customJlinkJdkSizeBytes,
        archiveType = jlinkJdkArchiveType(customJlinkJdkFileName),
        executableName = if (jlinkJdkPlatform.startsWith("windows-")) "jlink.exe" else "jlink",
    )
}
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
val generatedBuildInfo =
    generatedResourcesDirectory.map { it.file("org/glavo/ruyi/imager/update/build-info.properties") }
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

val generateBuildInfo = tasks.register<WriteProperties>("generateBuildInfo") {
    group = "build"
    description = "Writes the application version used at runtime."
    destinationFile = generatedBuildInfo.get().asFile
    property("version", project.version.toString())
}

val extractFastbootTasks = fastbootBundles.map { bundle ->
    val archive = layout.buildDirectory.file("downloads/${bundle.archiveFileName}")
    val configuredUrl = providers.gradleProperty("fastboot.${bundle.platformDirectory}.url").orElse(bundle.url)
    val configuredSha256 = providers.gradleProperty("fastboot.${bundle.platformDirectory}.sha256").orElse(bundle.sha256)
    val configuredSizeBytes = providers.gradleProperty("fastboot.${bundle.platformDirectory}.sizeBytes")
        .map { it.toLong() }
        .orElse(bundle.sizeBytes)
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

    val verifyTask = tasks.register<VerifyFile>("verify${bundle.taskSuffix}Fastboot") {
        group = "distribution"
        description = "Verifies Android Platform Tools for ${bundle.platformDirectory}."
        dependsOn(downloadTask)
        inputFile.set(archive)
        expectedSha256.set(configuredSha256)
        expectedSizeBytes.set(configuredSizeBytes)
    }

    tasks.register<ExtractZipEntries>("extract${bundle.taskSuffix}Fastboot") {
        group = "distribution"
        description = "Extracts Android Platform Tools for ${bundle.platformDirectory}."
        dependsOn(verifyTask)
        archiveFile.set(archive)
        entries.set(bundle.archiveEntries.associateWith { it.substringAfterLast('/') })
        executableFileNames.set(if (bundle.executableName == "fastboot") listOf(bundle.executableName) else emptyList())
        outputDirectory.set(bundledFastbootDirectory.map { it.dir(bundle.platformDirectory) })
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

val verifyJlinkJdk = tasks.register<VerifyFile>("verifyJlinkJdk") {
    group = "distribution"
    description = "Verifies the Liberica JDK archive used by jlink packaging."
    dependsOn(downloadJlinkJdk)
    inputFile.set(jlinkJdkArchive)
    expectedSha256.set(jlinkJdkBundle.sha256)
    expectedSizeBytes.set(jlinkJdkBundle.sizeBytes)
}

tasks.register<Copy>("extractJlinkJdk") {
    group = "distribution"
    description = "Extracts jlink and jmods from the downloaded Liberica JDK archive."
    dependsOn(verifyJlinkJdk)
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
        systemProperty(
            "ruyi.imager.update.source",
            providers.gradleProperty("update.manifest")
                .orElse(rootProject.layout.projectDirectory.file("update-manifest.local.json").asFile.absolutePath)
                .get(),
        )
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
    dependsOn(generateBuildInfo)
}

tasks.register<CreateJlinkRuntime>("jlinkRuntime") {
    group = "distribution"
    description = "Builds a custom Java runtime image for the current platform."
    dependsOn("extractJlinkJdk")
    executable.set(hostJlinkExecutable)
    jmodsDirectory.set(jlinkJmodsDirectory)
    modules.set(jlinkModules)
    requiredJavafxModules.set(
        providers.provider {
            if (jlinkRuntimeIncludesJavafx.get()) jlinkJavafxModuleNames else emptyList()
        },
    )
    outputDirectory.set(jlinkRuntimeDirectory)
}

val writeJlinkLaunchers = tasks.register<WriteJlinkLaunchers>("writeJlinkLaunchers") {
    group = "distribution"
    description = "Writes launch scripts for the jlink application image."
    mainClass.set(application.mainClass)
    jvmArguments.set(jlinkLauncherJvmArgs)
    outputDirectory.set(jlinkLaunchersDirectory)
}

tasks.register<Sync>("installJlinkDist") {
    group = "distribution"
    description = "Installs a jlink application image with runtime, libraries, launchers, and bundled tools."
    dependsOn("jar")
    dependsOn("jlinkRuntime")
    dependsOn(writeJlinkLaunchers)
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

val writeJlinkDebMetadata = tasks.register<WriteJlinkDebMetadata>("writeJlinkDebMetadata") {
    group = "distribution"
    description = "Writes Debian package launcher and desktop metadata."
    doFirst {
        requireDebianArchitecture(jlinkJdkPlatform)
    }
    outputDirectory.set(jlinkDebMetadataDirectory)
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

val writeJlinkDebControl = tasks.register<WriteDebControl>("writeJlinkDebControl") {
    group = "distribution"
    description = "Writes Debian package control metadata."
    dependsOn(prepareJlinkDebData)
    doFirst {
        requireDebianArchitecture(jlinkJdkPlatform)
    }
    dataDirectory.set(jlinkDebDataDirectory)
    packageName.set(jlinkDebPackageName)
    packageVersion.set(jlinkDebVersion)
    section.set("utils")
    priority.set("optional")
    architecture.set(providers.provider { requireDebianArchitecture(jlinkJdkPlatform) })
    maintainer.set(jlinkDebMaintainer)
    packageDescription.set("Ruyi Imager")
    longDescription.set("Ruyi Imager flashes Ruyi SDK catalog and local images to removable devices.")
    homepage.set(jlinkDebHomepage)
    outputDirectory.set(jlinkDebControlDirectory)
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

tasks.register<CreateDeb>("jlinkDeb") {
    group = "distribution"
    description = "Builds a Debian package from the jlink application image."
    dependsOn(jlinkDebControlTar)
    dependsOn(jlinkDebDataTar)
    doFirst {
        requireDebianArchitecture(jlinkJdkPlatform)
    }
    controlArchive.set(jlinkDebControlArchive)
    dataArchive.set(jlinkDebDataArchive)
    outputFile.set(jlinkDebOutputFile)
}

val writeJlinkWixSource = tasks.register<WriteWixSource>("writeJlinkWixSource") {
    group = "distribution"
    description = "Writes WiX source for the jlink application image."
    dependsOn("installJlinkDist")
    doFirst {
        requireWixArchitecture(jlinkJdkPlatform)
    }
    appDirectory.set(jlinkImageDirectory)
    iconFile.set(rootProject.layout.projectDirectory.file("resources/ruyi-logo.ico"))
    productName.set(jlinkMsiProductName)
    manufacturer.set(jlinkMsiManufacturer)
    productVersion.set(jlinkMsiProductVersion)
    upgradeCode.set(jlinkMsiUpgradeCode)
    installScope.set(jlinkMsiInstallScope)
    architecture.set(jlinkMsiArchitecture)
    outputFile.set(jlinkMsiSourceFile)
}

tasks.register<RunWixBuild>("jlinkMsi") {
    group = "distribution"
    description = "Builds a Windows Installer package from the jlink application image."
    dependsOn(writeJlinkWixSource)
    doFirst {
        requireWixArchitecture(jlinkJdkPlatform)
    }
    wixExecutable.set(jlinkMsiWixExecutable)
    architecture.set(jlinkMsiArchitecture)
    appDirectory.set(jlinkImageDirectory)
    sourceFile.set(jlinkMsiSourceFile)
    outputFile.set(jlinkMsiOutputFile)
}

val writeJlinkWixBundleSource = tasks.register<WriteWixBundleSource>("writeJlinkWixBundleSource") {
    group = "distribution"
    description = "Writes WiX Burn bundle source for the jlink MSI package."
    dependsOn("jlinkMsi")
    doFirst {
        requireWixArchitecture(jlinkJdkPlatform)
    }
    msiPackageFile.set(jlinkMsiOutputFile)
    iconFile.set(rootProject.layout.projectDirectory.file("resources/ruyi-logo.ico"))
    logoFile.set(rootProject.layout.projectDirectory.file("resources/ruyi-logo-64.png"))
    themeFile.set(rootProject.layout.projectDirectory.file("resources/wix/setup/theme.xml"))
    localizationDirectory.set(jlinkSetupLocalizationDirectory)
    productName.set(jlinkMsiProductName)
    manufacturer.set(jlinkMsiManufacturer)
    productVersion.set(jlinkMsiProductVersion)
    displayVersion.set(project.version.toString())
    upgradeCode.set(jlinkSetupUpgradeCode)
    installScope.set(jlinkMsiInstallScope)
    outputFile.set(jlinkSetupSourceFile)
}

tasks.register<RunWixBundleBuild>("jlinkSetupExe") {
    group = "distribution"
    description = "Builds a WiX Burn setup executable from the jlink MSI package."
    dependsOn(writeJlinkWixBundleSource)
    doFirst {
        requireWixArchitecture(jlinkJdkPlatform)
    }
    wixExecutable.set(jlinkMsiWixExecutable)
    sourceFile.set(jlinkSetupSourceFile)
    msiPackageFile.set(jlinkMsiOutputFile)
    localizationDirectory.set(jlinkSetupLocalizationDirectory)
    outputFile.set(jlinkSetupOutputFile)
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

/// Creates the default Liberica JDK bundle descriptor for a jlink JDK platform.
///
/// @param platform jlink JDK platform token.
/// @param version Liberica JDK version string.
/// @return Liberica JDK bundle descriptor.
fun libericaJdkBundle(platform: String, version: String): JlinkJdkBundle {
    require(version == "25.0.3+11") {
        "No bundled integrity metadata is available for Liberica JDK $version; " +
            "configure jlink.jdk.url, jlink.jdk.sha256, and jlink.jdk.sizeBytes"
    }
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
    val (sha256, sizeBytes) = when (platform) {
        "windows-x86_64" ->
            "95f4297f10f91e8cde9ae64d4c6bf8ead1d1aa29121ddfae4e48a1d389cc5413" to 346_883_258L
        "windows-aarch64" ->
            "69031a8475b19d399d6dbc6a22f6e1c9746dc6cd045cf442de73e181c9fb027b" to 232_629_221L
        "macos-x86_64" ->
            "35acc63854594a66f064a95af774c05f62dbaf76a62863ae3ee9be97e65faa74" to 363_252_444L
        "macos-aarch64" ->
            "bc44ece32d6b969374769e9159285d06aa78d5a78596918cad3587f14cc44fc6" to 357_365_399L
        "linux-x86_64" ->
            "2d0e145c401d0e555e9b3aad977a3a700fbd666bc7d4524720267dd80eb3be42" to 407_455_906L
        "linux-aarch64" ->
            "bfd46890bb5611ee673a3db4c676518c2989bd5cf729094a6193c1557a308fb9" to 401_889_520L
        "linux-riscv64" ->
            "644d3cadd5d9dc4f71cf56dd3bffde0113c949b2bb9c802eca49a035b77ea033" to 205_027_987L
        else -> error("Unsupported jlink target platform: $platform")
    }
    return JlinkJdkBundle(
        platform = platform,
        url = "https://download.bell-sw.com/java/$version/$fileName",
        fileName = fileName,
        sha256 = sha256,
        sizeBytes = sizeBytes,
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
