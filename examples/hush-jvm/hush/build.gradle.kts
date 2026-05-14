import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    kotlin("jvm") version "2.0.21"
}

group = "klama.hush"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

val hushDir = layout.projectDirectory.dir("../../..")

abstract class CMakeBuildTask @Inject constructor(
    @get:Internal val execOperations: ExecOperations
) : DefaultTask() {
    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val buildDir: DirectoryProperty

    @TaskAction
    fun build() {
        val src = sourceDir.get().asFile
        val bld = buildDir.get().asFile
        if (!bld.exists()) bld.mkdirs()

        execOperations.exec {
            workingDir = bld
            commandLine("cmake", src.absolutePath, "-DHUSH_BUILD_CLI=OFF", "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=OFF")
        }

        execOperations.exec {
            workingDir = bld
            commandLine("cmake", "--build", ".", "--config", "Release")
        }
    }
}

val buildHushCore = tasks.register<CMakeBuildTask>("buildHushCore") {
    sourceDir.set(hushDir)
    buildDir.set(layout.buildDirectory.dir("hush_core_build"))
}

val buildHushJni = tasks.register<CMakeBuildTask>("buildHushJni") {
    dependsOn(buildHushCore)
    sourceDir.set(layout.projectDirectory.dir("src/main/cpp"))
    buildDir.set(layout.buildDirectory.dir("hush_jni_build"))
    
    doFirst {
        val coreBuildDir = buildHushCore.get().buildDir.get().asFile.absolutePath
        val src = sourceDir.get().asFile
        val bld = buildDir.get().asFile
        if (!bld.exists()) bld.mkdirs()

        execOperations.exec {
            workingDir = bld
            commandLine("cmake", src.absolutePath, "-DHUSH_CORE_BUILD_DIR=$coreBuildDir", "-DHUSH_ROOT_DIR=${hushDir.asFile.absolutePath}", "-DCMAKE_BUILD_TYPE=Release")
        }

        execOperations.exec {
            workingDir = bld
            commandLine("cmake", "--build", ".", "--config", "Release")
        }
    }
}

tasks.named("processResources") {
    dependsOn(buildHushJni)
    
    doLast {
        val buildDir = buildHushJni.get().buildDir.get().asFile
        val resourcesDir = layout.buildDirectory.dir("resources/main").get().asFile
        
        val libFile = buildDir.listFiles()?.find { it.name.endsWith(".so") || it.name.endsWith(".dll") || it.name.endsWith(".dylib") }
        if (libFile != null) {
            libFile.copyTo(File(resourcesDir, libFile.name), overwrite = true)
        }
    }
}

