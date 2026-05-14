import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.gradle.api.tasks.Exec
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.androidKmpLibrary)
}

group = "klama"

val hushDirProvider = layout.projectDirectory.dir("../../..")

abstract class CMakeBuildTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {
    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val buildDir: DirectoryProperty

    @get:Input
    abstract val cmakeArgs: ListProperty<String>

    @TaskAction
    fun build() {
        val src = sourceDir.get().asFile
        val bld = buildDir.get().asFile
        if (!bld.exists()) bld.mkdirs()

        execOperations.exec {
            workingDir = bld
            commandLine("cmake", src.absolutePath, *cmakeArgs.get().toTypedArray())
        }

        execOperations.exec {
            workingDir = bld
            commandLine("cmake", "--build", ".", "--config", "Release")
        }
    }
}

val buildHushLinuxX64 = tasks.register<CMakeBuildTask>("buildHushLinuxX64") {
    sourceDir.set(hushDirProvider)
    buildDir.set(layout.buildDirectory.dir("hush_build_linux_x64"))
    cmakeArgs.set(listOf(
        "-DHUSH_BUILD_CLI=OFF",
        "-DCMAKE_BUILD_TYPE=Release"
    ))
}

val ndkDirProvider = project.provider {
    val localProps = Properties()
    val localPropsFile = project.rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localProps.load(localPropsFile.inputStream())
    }
    val sdkDir = localProps.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "${System.getProperty("user.home")}/Android/Sdk"
    val ndkParent = project.file("$sdkDir/ndk")
    val versions = ndkParent.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
    versions?.firstOrNull()?.absolutePath ?: throw GradleException("No NDK found in $sdkDir/ndk")
}

val buildHushAndroidArm64 = tasks.register<CMakeBuildTask>("buildHushAndroidArm64") {
    sourceDir.set(hushDirProvider)
    buildDir.set(layout.buildDirectory.dir("hush_build_android_arm64"))
    cmakeArgs.set(ndkDirProvider.map { ndkDir ->
        listOf(
            "-DHUSH_BUILD_CLI=OFF",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_TOOLCHAIN_FILE=$ndkDir/build/cmake/android.toolchain.cmake",
            "-DANDROID_ABI=arm64-v8a",
            "-DANDROID_PLATFORM=android-26"
        )
    })
}

val buildHushAndroidX64 = tasks.register<CMakeBuildTask>("buildHushAndroidX64") {
    sourceDir.set(hushDirProvider)
    buildDir.set(layout.buildDirectory.dir("hush_build_android_x64"))
    cmakeArgs.set(ndkDirProvider.map { ndkDir ->
        listOf(
            "-DHUSH_BUILD_CLI=OFF",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_TOOLCHAIN_FILE=$ndkDir/build/cmake/android.toolchain.cmake",
            "-DANDROID_ABI=x86_64",
            "-DANDROID_PLATFORM=android-26"
        )
    })
}

val copyHushJniLibs = tasks.register<Sync>("copyHushJniLibs") {
    dependsOn("linkReleaseSharedLinuxX64")
    into(layout.buildDirectory.dir("processedResources/jvm/main/bin"))
    from(layout.buildDirectory.dir("bin/linuxX64/releaseShared")) {
        include("libklama_hush_jni.so")
    }
}

val copyHushAndroidNativeLibs = tasks.register<Sync>("copyHushAndroidNativeLibs") {
    dependsOn("linkReleaseSharedAndroidNativeArm64", "linkReleaseSharedAndroidNativeX64")
    into(layout.buildDirectory.dir("jniLibs"))

    val toolchainLibPathProvider = ndkDirProvider.map { ndkDir ->
        "$ndkDir/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"
    }

    // ARM64
    into("arm64-v8a") {
        from(layout.buildDirectory.dir("bin/androidNativeArm64/releaseShared")) {
            include("libklama_hush_android.so")
        }
        from(toolchainLibPathProvider.map { "$it/aarch64-linux-android" }) {
            include("libc++_shared.so")
        }
    }
    // X64
    into("x86_64") {
        from(layout.buildDirectory.dir("bin/androidNativeX64/releaseShared")) {
            include("libklama_hush_android.so")
        }
        from(toolchainLibPathProvider.map { "$it/x86_64-linux-android" }) {
            include("libc++_shared.so")
        }
    }
}

kotlin {
    withSourcesJar(publish = true)
    android {
        packaging {
            jniLibs {
                pickFirsts += "**/libklama_hush_android.so"
                pickFirsts += "**/libc++_shared.so"
            }
        }
        namespace = "klama.hush"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvmToolchain(libs.versions.javaVersion.get().toInt())
    jvm()
    
    linuxX64 {
        binaries {
            sharedLib {
                baseName = "klama_hush_jni"
            }
        }
    }
    androidNativeArm64 {
        binaries {
            sharedLib {
                baseName = "klama_hush_android"
            }
        }
    }
    androidNativeX64 {
        binaries {
            sharedLib {
                baseName = "klama_hush_android"
            }
        }
    }

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        val isAndroid = konanTarget == org.jetbrains.kotlin.konan.target.KonanTarget.ANDROID_ARM64 ||
                konanTarget == org.jetbrains.kotlin.konan.target.KonanTarget.ANDROID_X64

        val hushBuildDir = when {
            konanTarget == org.jetbrains.kotlin.konan.target.KonanTarget.ANDROID_ARM64 -> buildHushAndroidArm64.get().buildDir
            konanTarget == org.jetbrains.kotlin.konan.target.KonanTarget.ANDROID_X64 -> buildHushAndroidX64.get().buildDir
            konanTarget == org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_X64 -> buildHushLinuxX64.get().buildDir
            else -> null
        }

        val hushBuildTask = when {
            konanTarget == org.jetbrains.kotlin.konan.target.KonanTarget.ANDROID_ARM64 -> buildHushAndroidArm64
            konanTarget == org.jetbrains.kotlin.konan.target.KonanTarget.ANDROID_X64 -> buildHushAndroidX64
            konanTarget == org.jetbrains.kotlin.konan.target.KonanTarget.LINUX_X64 -> buildHushLinuxX64
            else -> null
        }

        compilations.getByName("main") {
            val jni by cinterops.creating {
                definitionFile.set(
                    project.file(
                        if (isAndroid) "src/nativeInterop/cinterop/jni_android.def"
                        else "src/nativeInterop/cinterop/jni.def"
                    )
                )
                if (!isAndroid) {
                    val javaHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME")
                    if (javaHome != null) {
                        val os = if (org.gradle.internal.os.OperatingSystem.current().isMacOsX()) "darwin" else "linux"
                        compilerOpts("-I$javaHome/include", "-I$javaHome/include/$os")
                    }
                }
            }

            val hush_interop by cinterops.creating {
                definitionFile.set(
                    project.file(
                        if (isAndroid) "src/nativeInterop/cinterop/hush_android.def"
                        else "src/nativeInterop/cinterop/hush.def"
                    )
                )
                includeDirs(hushDirProvider.asFile.resolve("include"))
                if (hushBuildDir != null && hushBuildTask != null) {
                    val hushLibPath = hushBuildDir.get().asFile.absolutePath
                    extraOpts("-libraryPath", hushLibPath)
                    tasks.named(interopProcessingTaskName) {
                        dependsOn(hushBuildTask)
                        inputs.dir(hushLibPath)
                    }
                }
            }
        }

        if (hushBuildDir != null && hushBuildTask != null) {
            binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary> {
                val hushLibDir = hushBuildDir.get().asFile.absolutePath
                linkerOpts("-L$hushLibDir")
                
                linkTaskProvider.configure {
                    dependsOn(hushBuildTask)
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.3.3")
            }
        }

        val nativeMain by creating
        val jvmMain by getting
        val linuxX64Main by getting
        val androidNativeMain by creating
        val androidNativeArm64Main by getting
        val androidNativeX64Main by getting

        val androidMain by getting {
            dependencies {
                implementation("androidx.core:core-ktx:1.9.0")
            }
            resources.srcDirs(copyHushAndroidNativeLibs)
        }
    }
}

tasks.named("androidPreBuild") {
    dependsOn(copyHushAndroidNativeLibs)
}

tasks.named("jvmProcessResources") {
    dependsOn(copyHushJniLibs)
}
