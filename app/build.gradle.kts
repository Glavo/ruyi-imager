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

val alibabaPuhuitiFontUrl =
    "https://registry.npmmirror.com/@fontpkg/alibaba-puhuiti-3-0/-/alibaba-puhuiti-3-0-0.0.0.tgz"
val alibabaPuhuitiFontArchive =
    rootProject.layout.buildDirectory.file("downloads/fonts/alibaba-puhuiti-3-0-0.0.0.tgz")

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

    javafx("base")?.let { implementation(it) }
    javafx("controls")?.let { implementation(it) }
    javafx("graphics")?.let { implementation(it) }

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "org.glavo.ruyi.imager.Main"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED,javafx.graphics")
}

distributions {
    main {
        contents {
            into("tools/fastboot") {
                from(bundledFastbootDirectory)
            }
        }
    }
}

tasks.named("installDist") {
    dependsOn("prepareBundledFastboot")
}

tasks.named("distZip") {
    dependsOn("prepareBundledFastboot")
}

tasks.named("distTar") {
    dependsOn("prepareBundledFastboot")
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED,javafx.graphics")
}

/// Returns the JavaFX dependency notation for the current build platform.
///
/// @param module JavaFX module name.
/// @return dependency notation, or null when the platform is unsupported.
fun javafx(module: String): String? {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    val javafxOS = when {
        osName.contains("win") -> "win"
        osName.contains("mac") -> "mac"
        osName.contains("linux") -> "linux"
        else -> null
    }
    val javafxArch = when (osArch) {
        "amd64", "x86-64", "x64" -> ""
        "aarch64", "arm64" -> "-aarch64"
        else -> null
    }

    return if (javafxOS == null || javafxArch == null) {
        null
    } else {
        "org.openjfx:javafx-$module:25.0.2:${javafxOS}${javafxArch}"
    }
}
