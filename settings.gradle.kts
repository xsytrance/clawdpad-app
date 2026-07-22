rootProject.name = "clawdpad"
include(":app")
include(":pokepad-app")
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
