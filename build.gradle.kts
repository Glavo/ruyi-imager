import java.time.Duration

plugins {
    base
}

group = "org.glavo"

val ruyiBaseVersion = providers.gradleProperty("ruyi.baseVersion").orElse("1.0")
val ruyiCommitVersion = providers.gradleProperty("ruyi.commitVersion")
    .map { value ->
        value.toBooleanStrictOrNull()
            ?: error("Invalid ruyi.commitVersion value: $value")
    }
    .orElse(false)
val ruyiCommit = providers.gradleProperty("ruyi.commit")
    .orElse(providers.environmentVariable("GITHUB_SHA"))
    .orElse(providers.exec {
        commandLine("git", "rev-parse", "HEAD")
    }.standardOutput.asText.map { it.trim() })

version = providers.gradleProperty("ruyi.version")
    .orElse(providers.provider {
        if (ruyiCommitVersion.get()) {
            "${ruyiBaseVersion.get()}.${shortCommit(ruyiCommit.get())}"
        } else {
            "${ruyiBaseVersion.get()}-SNAPSHOT"
        }
    })
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

fun shortCommit(commit: String): String {
    val value = commit.trim()
    require(value.length >= 7) {
        "Commit hash must contain at least 7 characters: $commit"
    }

    val shortCommit = value.substring(0, 7).lowercase()
    require(shortCommit.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Commit hash must start with hexadecimal characters: $commit"
    }
    return shortCommit
}
