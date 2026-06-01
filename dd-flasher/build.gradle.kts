plugins {
    base
}

val cargoExecutable = providers.gradleProperty("cargo.executable").orElse("cargo")
val crossExecutable = providers.gradleProperty("cross.executable").orElse("cross")
val ddFlasherBuildTool = providers.gradleProperty("ddFlasher.buildTool").orElse("cargo")
val rustTargetDirectory = layout.buildDirectory.dir("cargo-target")
val cargoConfigFile = rootProject.layout.projectDirectory.file(".cargo/config.toml")
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
data class DDFlasherPlatform(
    val directory: String,
    val rustTarget: String,
    val executableName: String,
)

/// Supported dd-flasher distribution platforms.
///
/// @return supported platforms.
fun supportedPlatforms(): List<DDFlasherPlatform> =
    listOf(
        DDFlasherPlatform("windows-x86_64", "x86_64-pc-windows-msvc", "dd-flasher.exe"),
        DDFlasherPlatform("windows-aarch64", "aarch64-pc-windows-msvc", "dd-flasher.exe"),
        DDFlasherPlatform("macos-x86_64", "x86_64-apple-darwin", "dd-flasher"),
        DDFlasherPlatform("macos-aarch64", "aarch64-apple-darwin", "dd-flasher"),
        DDFlasherPlatform("linux-x86_64", "x86_64-unknown-linux-gnu", "dd-flasher"),
        DDFlasherPlatform("linux-aarch64", "aarch64-unknown-linux-gnu", "dd-flasher"),
        DDFlasherPlatform("linux-riscv64", "riscv64gc-unknown-linux-gnu", "dd-flasher"),
    )

/// Returns the current platform used inside distributions.
///
/// @return platform, or null when unsupported.
fun currentPlatform(): DDFlasherPlatform? {
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
fun platform(directory: String): DDFlasherPlatform? =
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
val releaseExecutable = releaseExecutable(selectedPlatform, cargoRustTarget)
val bundledExecutable = bundledExecutable(selectedPlatform)

tasks.register<Exec>("cargoBuild") {
    group = "build"
    description = "Builds the Rust dd-flasher helper."
    configureRustBuild(selectedPlatform, cargoRustTarget)
    outputs.file(releaseExecutable)
}

val platformPrepareTasks = supportedPlatforms().map { platform ->
    val taskSuffix = platformTaskSuffix(platform.directory)
    val platformReleaseExecutable = releaseExecutable(platform, platform.rustTarget)
    val platformBundledExecutable = bundledExecutable(platform)
    val buildTask = tasks.register<Exec>("cargoBuild$taskSuffix") {
        group = "build"
        description = "Builds the Rust dd-flasher helper for ${platform.directory}."
        configureRustBuild(platform, platform.rustTarget)
        outputs.file(platformReleaseExecutable)
    }

    tasks.register<Copy>("prepareBundledDDFlasher$taskSuffix") {
        group = "distribution"
        description = "Copies the ${platform.directory} Rust dd-flasher helper into the application distribution layout."
        dependsOn(buildTask)
        from(platformReleaseExecutable)
        into(layout.buildDirectory.dir("bundled-dd-flasher/${platform.directory}"))
        rename { platform.executableName }
        doLast {
            if (!platform.directory.startsWith("windows-")) {
                platformBundledExecutable.get().asFile.setExecutable(true, false)
            }
        }
    }
}

tasks.register<Exec>("cargoTest") {
    group = "verification"
    description = "Runs Rust dd-flasher tests."
    workingDir = projectDir
    commandLine(cargoExecutable.get(), "test", "--target-dir", cargoTargetDirectoryPath.get())
}

val prepareBundledDDFlasher = tasks.register<Copy>("prepareBundledDDFlasher") {
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

tasks.register("prepareAllBundledDDFlashers") {
    group = "distribution"
    description = "Copies all supported-platform Rust dd-flasher helpers into the application distribution layout."
    dependsOn(platformPrepareTasks)
}

tasks.register("printDDFlasherTargets") {
    group = "help"
    description = "Prints supported dd-flasher target platforms and Rust target triples."
    doLast {
        for (platform in supportedPlatforms()) {
            val taskSuffix = platformTaskSuffix(platform.directory)
            println("${platform.directory} -> ${platform.rustTarget} (cargoBuild$taskSuffix)")
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

/// Returns the selected Rust build executable.
///
/// @return executable name or path.
fun rustBuildExecutable(): String =
    when (val buildTool = ddFlasherBuildTool.get()) {
        "cargo" -> cargoExecutable.get()
        "cross" -> crossExecutable.get()
        else -> throw GradleException("Unsupported dd-flasher build tool: $buildTool. Supported: cargo, cross")
    }

/// Configures one Rust release build task.
///
/// @param platform target platform metadata.
/// @param rustTarget Rust target triple, or null for the host default target.
fun Exec.configureRustBuild(platform: DDFlasherPlatform, rustTarget: String?) {
    workingDir = projectDir
    inputs.file(layout.projectDirectory.file("Cargo.toml"))
        .withPropertyName("cargoManifest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("Cargo.lock"))
        .withPropertyName("cargoLock")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(cargoConfigFile)
        .withPropertyName("cargoConfig")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rustSourceFiles)
        .withPropertyName("rustSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("cargoExecutable", cargoExecutable)
    inputs.property("crossExecutable", crossExecutable)
    inputs.property("ddFlasherBuildTool", ddFlasherBuildTool)
    inputs.property("rustTarget", rustTarget ?: "")
    inputs.property("platform", platform.directory)
    val command = mutableListOf(rustBuildExecutable(), "build", "--release", "--target-dir", cargoTargetDirectoryPath.get())
    if (rustTarget != null) {
        command.addAll(listOf("--target", rustTarget))
    }
    commandLine(command)
}

/// Returns the release executable path for a platform build.
///
/// @param platform target platform metadata.
/// @param rustTarget Rust target triple, or null for the host default target.
/// @return release executable provider.
fun releaseExecutable(platform: DDFlasherPlatform, rustTarget: String?) =
    rustTargetDirectory.map {
        if (rustTarget == null) {
            it.file("release/${platform.executableName}")
        } else {
            it.file("$rustTarget/release/${platform.executableName}")
        }
    }

/// Returns the bundled executable path for a platform.
///
/// @param platform target platform metadata.
/// @return bundled executable provider.
fun bundledExecutable(platform: DDFlasherPlatform) =
    layout.buildDirectory.file("bundled-dd-flasher/${platform.directory}/${platform.executableName}")

/// Converts a platform directory name into a Gradle task suffix.
///
/// @param platformDirectory distribution platform directory.
/// @return Gradle task suffix.
fun platformTaskSuffix(platformDirectory: String): String =
    platformDirectory.split('-', '_').joinToString("") { token ->
        token.replaceFirstChar { it.uppercaseChar() }
    }
