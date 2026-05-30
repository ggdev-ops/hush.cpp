plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

group = "myai"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":hush"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.cliKt)
}

application {
    mainClass.set("myai.hush.cli.MainKt")
}

kotlin {
    jvmToolchain(libs.versions.javaVersion.get().toInt())
}

