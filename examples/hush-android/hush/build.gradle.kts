import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}


android {
    namespace = "klama.hush.android.lib"
    compileSdk = 34
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DHUSH_ROOT_DIR=${project.rootDir.absolutePath}/../.."
                arguments += "-Dhush_BUILD_CLI=OFF"
                arguments += "-Dhush_BUILD_TESTS=OFF"
                arguments += "-Dhush_BUILD_EXAMPLES=OFF"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}


