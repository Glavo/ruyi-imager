plugins {
    base
}

val cargoExecutable = providers.gradleProperty("cargo.executable").orElse("cargo")
val rustTargetDirectory = layout.buildDirectory.dir("cargo-target")
val currentBundledPlatformDirectory = currentPlatformDirectory()
    ?: throw GradleException("Unsupported dd-flasher build platform: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")

/// Returns the current platform directory used inside distributions.
///
/// @return platform directory, or null when unsupported.
fun currentPlatformDirectory(): String? {
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
    return if (os == null || arch == null) null else "$os-$arch"
}

/// Returns the executable file name for the current platform.
///
/// @return executable file name.
fun executableName(): String =
    if (isWindowsOs(System.getProperty("os.name").lowercase())) "dd-flasher.exe" else "dd-flasher"

/// Returns whether an OS name identifies Windows.
///
/// @param osName normalized operating system name.
/// @return whether the OS is Windows.
fun isWindowsOs(osName: String): Boolean =
    osName.startsWith("windows")

val cargoTargetDirectoryPath = rustTargetDirectory.map { it.asFile.absolutePath }
val releaseExecutable = rustTargetDirectory.map {
    it.file("release/${executableName()}")
}
val bundledExecutable = layout.buildDirectory.file("bundled-dd-flasher/$currentBundledPlatformDirectory/${executableName()}")

tasks.register<Exec>("cargoBuild") {
    group = "build"
    description = "Builds the Rust dd-flasher helper."
    workingDir = projectDir
    commandLine(cargoExecutable.get(), "build", "--release", "--target-dir", cargoTargetDirectoryPath.get())
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
    description = "Copies the current-platform Rust dd-flasher helper into the application distribution layout."
    dependsOn("cargoBuild")
    from(releaseExecutable)
    into(layout.buildDirectory.dir("bundled-dd-flasher/$currentBundledPlatformDirectory"))
    rename { executableName() }
    doLast {
        if (!isWindowsOs(System.getProperty("os.name").lowercase())) {
            bundledExecutable.get().asFile.setExecutable(true, false)
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
