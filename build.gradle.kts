import java.time.Duration
import java.util.Properties

plugins {
    base
    id("org.glavo.gradle-wrapper-neo") version "0.2.0"
}

group = "org.glavo"

val versionBasePattern = Regex("""[0-9]+\.[0-9]+\.[0-9]+""")
val versionQualifierPattern = Regex("""[0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*""")
val versionMetadataPattern = Regex("""[0-9A-Za-z_.+]+""")
val trackedProjectProperties = Properties().apply {
    providers.fileContents(layout.projectDirectory.file("gradle/project.properties"))
        .asText.get().reader().use(::load)
}

val composedVersion = providers.provider {
    val base = trackedProjectProperties.getProperty("ruyiVersionBase")
        ?: error("ruyiVersionBase is missing from gradle/project.properties")
    val baseComponents = base.split('.')
    val validBase = versionBasePattern.matches(base) &&
            baseComponents[0].toLongOrNull()?.let { it <= 255L } == true &&
            baseComponents[1].toLongOrNull()?.let { it <= 255L } == true &&
            baseComponents[2].toLongOrNull()?.let { it <= 65_535L } == true
    require(validBase) {
        "ruyiVersionBase must use major/minor values up to 255 and a patch value up to 65535: $base"
    }

    val qualifier = providers.gradleProperty("ruyiVersionQualifier").orNull
    require(qualifier == null || versionQualifierPattern.matches(qualifier)) {
        "Invalid ruyiVersionQualifier: $qualifier"
    }

    val metadata = providers.gradleProperty("ruyiVersionMetadata").orNull
    require(metadata == null || versionMetadataPattern.matches(metadata)) {
        "Invalid ruyiVersionMetadata: $metadata"
    }

    buildString {
        append(base)
        if (qualifier != null) {
            append('-')
            append(qualifier)
        }
        if (metadata != null) {
            append('+')
            append(metadata)
        }
    }
}

version = providers.gradleProperty("ruyi.version")
    .orElse(composedVersion)
    .get()

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(25)
            }
        }

        tasks.withType<Test>() {
            useJUnitPlatform()
            timeout.set(Duration.ofMinutes(10))
            val testTmpDir = layout.buildDirectory.dir("tmp/test-tmp")
            systemProperty("java.io.tmpdir", testTmpDir.get().asFile.absolutePath)
            doFirst {
                testTmpDir.get().asFile.mkdirs()
            }
        }
    }
}
