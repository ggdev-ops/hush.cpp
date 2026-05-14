plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    id("com.android.library") version "9.0.1" apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}

val publishedModules = mapOf(
    ":whisper" to "klama-whisper",
    ":core" to "klama-core",
    ":KGguf:lib" to "klama-gguf",
    ":clients:koog" to "klama-koog"
)

subprojects {
    val artifactId = publishedModules[project.path]
    if (artifactId != null) {
        apply(plugin = "com.vanniktech.maven.publish")
        
        configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            coordinates(project.group.toString(), artifactId, project.version.toString())
            
            pom {
                name.set(artifactId)
                description.set("Klama AI Library - ${artifactId}")
                inceptionYear.set("2026")
                url.set("https://github.com/ggdev-ops/klama")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("ggdev-ops")
                        name.set("Ahmed Sami")
                    }
                }
                scm {
                    url.set("https://github.com/ggdev-ops/klama")
                }
            }
        }
    }
}


