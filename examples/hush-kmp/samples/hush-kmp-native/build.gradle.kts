plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "klama"
version = "1.0-SNAPSHOT"

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint = "klama.hush.native.main"
            }
        }
    }
    
    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                implementation(project(":hush"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

