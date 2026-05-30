plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "myai"
version = "1.0-SNAPSHOT"

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint = "myai.hush.native.main"
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

