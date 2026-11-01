pluginManagement {
    // xyz.kyngs.libby.plugin can no longer be downloaded (the upstream repository is archived and
    // repo.kyngs.xyz returns 404 for the plugin jar), so it is built from source.
    includeBuild("build-logic/libby-gradle-plugin")
    repositories {
        maven {
            url = uri("https://repo.kyngs.xyz/gradle-plugins")
        }
        gradlePluginPortal()
    }
}

rootProject.name = "LibreLoginX"
include("Plugin", "API")
