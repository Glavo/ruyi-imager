import java.time.Duration

plugins {
    base
}

group = "org.glavo"
version = "1.0-SNAPSHOT"

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

        tasks.withType<Test>().configureEach {
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
