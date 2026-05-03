plugins {
    id("java-library")
}

group = "org.glavo"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    compileOnlyApi("org.jetbrains:annotations:26.1.0")

    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()

    val javafxVersion = "21.0.10"
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

            compileOnly(notation)
            testCompileOnly(notation)
            testRuntimeOnly(notation)
        }
    }

    javafx("base")
    javafx("controls")
    javafx("graphics")
    javafx("swing") // For Benchmark

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}