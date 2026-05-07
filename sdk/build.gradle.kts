plugins {
    `java-library`
}

dependencies {
    compileOnlyApi("org.jetbrains:annotations:26.1.0")
    testCompileOnly("org.jetbrains:annotations:26.1.0")

    api("org.slf4j:slf4j-api:2.0.17")

    api(project(":dd-flasher"))

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
    testRuntimeOnly("org.slf4j:slf4j-jdk14:2.0.17")
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
