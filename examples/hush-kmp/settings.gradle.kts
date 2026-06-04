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
include(":hush-kmp-app:commonApp")
include(":hush-kmp-app:desktopApp")
include(":hush-kmp-app:androidApp")
include("samples:hush-kmp-cli")
include("samples:hush-kmp-native")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

