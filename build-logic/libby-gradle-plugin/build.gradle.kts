plugins {
    `java-gradle-plugin`
}

version = "1.2.1"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.grack:nanojson:1.7")

    // Compile against the Shadow plugin the main build applies (Plugin/build.gradle.kts), so the
    // ShadowJar relocation API used by ShadowPluginIntegration is checked at compile time.
    compileOnly("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:8.3.11")
}

gradlePlugin {
    plugins {
        create("xyz.kyngs.libby.plugin") {
            id = "xyz.kyngs.libby.plugin"
            implementationClass = "xyz.kyngs.libby.plugin.LibbyGradlePlugin"
        }
    }
}

tasks.withType<JavaCompile> {
    options.release.set(17)
    options.compilerArgs.add("-parameters")
}
