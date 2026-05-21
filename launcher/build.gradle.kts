plugins {
    base
}

val cargoExecutable = providers.gradleProperty("cargo.executable").orElse("cargo")
val rustTargetDirectory = layout.buildDirectory.dir("cargo-target")
val rustSourceFiles = fileTree(layout.projectDirectory.dir("src")) {
    include("**/*.rs")
}

/// Native launcher platform metadata.
///
/// @param directory distribution platform directory.
/// @param rustTarget Rust target triple.
/// @param executableName output executable name.
data class LauncherPlatform(
    val directory: String,
    val rustTarget: String,
    val executableName: String,
)

/// Supported native launcher distribution platforms.
///
/// @return supported platforms.
fun supportedPlatforms(): List<LauncherPlatform> =
    listOf(
        LauncherPlatform("windows-x86_64", "x86_64-pc-windows-msvc", "ruyi-imager.exe"),
        LauncherPlatform("windows-aarch64", "aarch64-pc-windows-msvc", "ruyi-imager.exe"),
    )

val cargoTargetDirectoryPath = rustTargetDirectory.map { it.asFile.absolutePath }

val platformPrepareTasks = supportedPlatforms().map { platform ->
    val taskSuffix = platformTaskSuffix(platform.directory)
    val platformReleaseExecutable = releaseExecutable(platform)
    val platformBundledExecutable = bundledExecutable(platform)
    val buildTask = tasks.register<Exec>("cargoBuild$taskSuffix") {
        group = "build"
        description = "Builds the Rust native launcher for ${platform.directory}."
        configureRustBuild(platform)
        outputs.file(platformReleaseExecutable)
    }

    tasks.register<Copy>("prepareBundledLauncher$taskSuffix") {
        group = "distribution"
        description = "Copies the ${platform.directory} Rust native launcher into the application distribution layout."
        dependsOn(buildTask)
        from(platformReleaseExecutable)
        into(layout.buildDirectory.dir("bundled-launcher/${platform.directory}"))
        rename { platform.executableName }
        outputs.file(platformBundledExecutable)
    }
}

tasks.register<Exec>("cargoTest") {
    group = "verification"
    description = "Runs Rust native launcher tests."
    workingDir = projectDir
    commandLine(cargoExecutable.get(), "test", "--target-dir", cargoTargetDirectoryPath.get())
}

tasks.register("prepareAllBundledLaunchers") {
    group = "distribution"
    description = "Copies all supported-platform Rust native launchers into the application distribution layout."
    dependsOn(platformPrepareTasks)
}

tasks.register("printLauncherTargets") {
    group = "help"
    description = "Prints supported native launcher target platforms and Rust target triples."
    doLast {
        for (platform in supportedPlatforms()) {
            val taskSuffix = platformTaskSuffix(platform.directory)
            println("${platform.directory} -> ${platform.rustTarget} (cargoBuild$taskSuffix)")
        }
    }
}

tasks.register("test") {
    group = "verification"
    description = "Runs Rust native launcher tests."
    dependsOn("cargoTest")
}

/// Configures one Rust release build task.
///
/// @param platform target platform metadata.
fun Exec.configureRustBuild(platform: LauncherPlatform) {
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
    inputs.property("rustTarget", platform.rustTarget)
    inputs.property("platform", platform.directory)
    commandLine(
        cargoExecutable.get(),
        "build",
        "--release",
        "--target-dir",
        cargoTargetDirectoryPath.get(),
        "--target",
        platform.rustTarget,
    )
}

/// Returns the release executable path for a platform build.
///
/// @param platform target platform metadata.
/// @return release executable provider.
fun releaseExecutable(platform: LauncherPlatform) =
    rustTargetDirectory.map { it.file("${platform.rustTarget}/release/${platform.executableName}") }

/// Returns the bundled executable path for a platform.
///
/// @param platform target platform metadata.
/// @return bundled executable provider.
fun bundledExecutable(platform: LauncherPlatform) =
    layout.buildDirectory.file("bundled-launcher/${platform.directory}/${platform.executableName}")

/// Converts a platform directory name into a Gradle task suffix.
///
/// @param platformDirectory distribution platform directory.
/// @return Gradle task suffix.
fun platformTaskSuffix(platformDirectory: String): String =
    platformDirectory.split('-', '_').joinToString("") { token ->
        token.replaceFirstChar { it.uppercaseChar() }
    }
