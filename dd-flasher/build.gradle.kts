plugins {
    base
}

val cargoExecutable = providers.gradleProperty("cargo.executable").orElse("cargo")
val rustTargetDirectory = layout.buildDirectory.dir("cargo-target")
val rustSourceFiles = fileTree(layout.projectDirectory.dir("src")) {
    include("**/*.rs")
}
val currentBundledPlatform = currentPlatform()
    ?: throw GradleException("Unsupported dd-flasher build platform: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
val requestedTargetPlatform = providers.gradleProperty("ddFlasher.targetPlatform").orNull
val selectedPlatform = platform(requestedTargetPlatform ?: currentBundledPlatform.directory)
    ?: throw GradleException("Unsupported dd-flasher target platform: ${requestedTargetPlatform}. Supported: ${supportedPlatformNames()}")
val cargoRustTarget = providers.gradleProperty("ddFlasher.rustTarget").orNull
    ?: if (requestedTargetPlatform == null && selectedPlatform.directory == currentBundledPlatform.directory) null else selectedPlatform.rustTarget

/// dd-flasher platform metadata used for native and cross builds.
///
/// @param directory distribution platform directory.
/// @param rustTarget Rust target triple.
/// @param executableName output executable name.
data class DdFlasherPlatform(
    val directory: String,
    val rustTarget: String,
    val executableName: String,
)

/// Supported dd-flasher distribution platforms.
///
/// @return supported platforms.
fun supportedPlatforms(): List<DdFlasherPlatform> =
    listOf(
        DdFlasherPlatform("windows-x86_64", "x86_64-pc-windows-msvc", "dd-flasher.exe"),
        DdFlasherPlatform("windows-aarch64", "aarch64-pc-windows-msvc", "dd-flasher.exe"),
        DdFlasherPlatform("macos-x86_64", "x86_64-apple-darwin", "dd-flasher"),
        DdFlasherPlatform("macos-aarch64", "aarch64-apple-darwin", "dd-flasher"),
        DdFlasherPlatform("linux-x86_64", "x86_64-unknown-linux-gnu", "dd-flasher"),
        DdFlasherPlatform("linux-aarch64", "aarch64-unknown-linux-gnu", "dd-flasher"),
        DdFlasherPlatform("linux-riscv64", "riscv64gc-unknown-linux-gnu", "dd-flasher"),
    )

/// Returns the current platform used inside distributions.
///
/// @return platform, or null when unsupported.
fun currentPlatform(): DdFlasherPlatform? {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    val os = when {
        isWindowsOs(osName) -> "windows"
        osName.contains("mac") || osName.contains("darwin") -> "macos"
        osName.contains("linux") -> "linux"
        else -> null
    }
    val arch = when (osArch) {
        "amd64", "x86_64", "x86-64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        "riscv64", "risc-v64", "riscv64gc" -> "riscv64"
        else -> null
    }
    return if (os == null || arch == null) null else platform("$os-$arch")
}

/// Resolves a supported platform by distribution directory.
///
/// @param directory distribution platform directory.
/// @return platform metadata, or null when unsupported.
fun platform(directory: String): DdFlasherPlatform? =
    supportedPlatforms().firstOrNull { it.directory == directory }

/// Returns supported platform names for diagnostics.
///
/// @return comma-separated platform names.
fun supportedPlatformNames(): String =
    supportedPlatforms().joinToString(", ") { it.directory }

/// Returns whether an OS name identifies Windows.
///
/// @param osName normalized operating system name.
/// @return whether the OS is Windows.
fun isWindowsOs(osName: String): Boolean =
    osName.startsWith("windows")

val cargoTargetDirectoryPath = rustTargetDirectory.map { it.asFile.absolutePath }
val releaseExecutable = rustTargetDirectory.map {
    if (cargoRustTarget == null) {
        it.file("release/${selectedPlatform.executableName}")
    } else {
        it.file("$cargoRustTarget/release/${selectedPlatform.executableName}")
    }
}
val bundledExecutable = layout.buildDirectory.file("bundled-dd-flasher/${selectedPlatform.directory}/${selectedPlatform.executableName}")

tasks.register<Exec>("cargoBuild") {
    group = "build"
    description = "Builds the Rust dd-flasher helper."
    workingDir = projectDir
    inputs.file(layout.projectDirectory.file("Cargo.toml"))
        .withPropertyName("cargoManifest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("Cargo.lock"))
        .withPropertyName("cargoLock")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rustSourceFiles)
        .withPropertyName("rustSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("cargoExecutable", cargoExecutable)
    inputs.property("cargoRustTarget", cargoRustTarget ?: "")
    inputs.property("selectedPlatform", selectedPlatform.directory)
    val command = mutableListOf(cargoExecutable.get(), "build", "--release", "--target-dir", cargoTargetDirectoryPath.get())
    if (cargoRustTarget != null) {
        command.addAll(listOf("--target", cargoRustTarget))
    }
    commandLine(command)
    outputs.file(releaseExecutable)
}

tasks.register<Exec>("cargoTest") {
    group = "verification"
    description = "Runs Rust dd-flasher tests."
    workingDir = projectDir
    commandLine(cargoExecutable.get(), "test", "--target-dir", cargoTargetDirectoryPath.get())
}

val prepareBundledDdFlasher = tasks.register<Copy>("prepareBundledDdFlasher") {
    group = "distribution"
    description = "Copies the selected-platform Rust dd-flasher helper into the application distribution layout."
    dependsOn("cargoBuild")
    from(releaseExecutable)
    into(layout.buildDirectory.dir("bundled-dd-flasher/${selectedPlatform.directory}"))
    rename { selectedPlatform.executableName }
    doLast {
        if (!selectedPlatform.directory.startsWith("windows-")) {
            bundledExecutable.get().asFile.setExecutable(true, false)
        }
    }
}

tasks.register("printDdFlasherTargets") {
    group = "help"
    description = "Prints supported dd-flasher target platforms and Rust target triples."
    doLast {
        for (platform in supportedPlatforms()) {
            println("${platform.directory} -> ${platform.rustTarget}")
        }
    }
}

tasks.register("test") {
    group = "verification"
    description = "Runs Rust dd-flasher tests."
    dependsOn("cargoTest")
}

tasks.named("assemble") {
    dependsOn("cargoBuild")
}

tasks.named("check") {
    dependsOn("cargoTest")
}
