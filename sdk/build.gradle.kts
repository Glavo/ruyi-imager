plugins {
    `java-library`
}

dependencies {
    compileOnlyApi("org.jetbrains:annotations:26.1.0")
    testCompileOnly("org.jetbrains:annotations:26.1.0")

    javafx("base")?.let { api(it) }

    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r")
    implementation("org.glavo.kala:kala-compress-archivers-tar:1.27.1-3")
    implementation("org.glavo.kala:kala-compress-compressors-bzip2:1.27.1-3")
    implementation("org.glavo.kala:kala-compress-compressors-lz4:1.27.1-3")
    implementation("org.glavo.kala:kala-compress-compressors-xz:1.27.1-3")
    implementation("org.glavo.kala:kala-compress-compressors-zstandard:1.27.1-3")
    implementation("org.tomlj:tomlj:1.1.1")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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
