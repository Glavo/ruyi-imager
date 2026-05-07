plugins {
    `java-library`
}

dependencies {
    compileOnlyApi("org.jetbrains:annotations:26.1.0")
    testCompileOnly("org.jetbrains:annotations:26.1.0")

    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-jdk14:2.0.17")
}
