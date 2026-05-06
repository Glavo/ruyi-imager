import org.gradle.api.file.RelativePath
import java.net.URI
import java.time.Duration

plugins {
    application
}

group = "org.glavo"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
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
    compileOnly("org.jetbrains:annotations:26.1.0")
    testCompileOnly("org.jetbrains:annotations:26.1.0")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("info.picocli:picocli:4.7.7")
    implementation("io.github.palexdev:materialfx:21.18.0-alpha")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r")
    implementation("org.glavo.kala:kala-compress-archivers-tar:1.27.1-3")
    implementation("org.glavo.kala:kala-compress-compressors-bzip2:1.27.1-3")
    implementation("org.glavo.kala:kala-compress-compressors-lz4:1.27.1-3")
    implementation("org.glavo.kala:kala-compress-compressors-xz:1.27.1-3")
    implementation("org.glavo.kala:kala-compress-compressors-zstandard:1.27.1-3")
    implementation("org.tomlj:tomlj:1.1.1")

    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()

    val javafxVersion = "25.0.2"
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

    fun javafx(module: String) {
        if (javafxOS != null && javafxArch != null) {
            val notation = "org.openjfx:javafx-$module:$javafxVersion:${javafxOS}${javafxArch}"

            implementation(notation)
        }
    }

    javafx("base")
    javafx("controls")
    javafx("graphics")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "org.glavo.ruyi.imager.Main"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
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
    useJUnitPlatform()
    timeout.set(Duration.ofMinutes(10))
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val testTmpDir = layout.buildDirectory.dir("tmp/test-tmp")
    systemProperty("java.io.tmpdir", testTmpDir.get().asFile.absolutePath)
    doFirst {
        testTmpDir.get().asFile.mkdirs()
    }
}
