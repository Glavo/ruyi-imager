import java.time.Duration

plugins {
    base
    id("org.glavo.gradle-wrapper-neo") version "0.2.0"
}

group = "org.glavo"

version = providers.gradleProperty("ruyi.version")
    .orElse("1.0-SNAPSHOT")
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
