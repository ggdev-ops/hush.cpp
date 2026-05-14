pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "hush-kmp"
include(":hush")
include("samples:hush-kmp-app:commonApp")
include("samples:hush-kmp-app:desktopApp")
include("samples:hush-kmp-app:androidApp")
include("samples:hush-kmp-cli")
include("samples:hush-kmp-native")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

