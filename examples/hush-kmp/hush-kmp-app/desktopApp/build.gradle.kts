plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    application
}

dependencies {
    implementation(project(":hush-kmp-app:commonApp"))
    implementation(compose.desktop.currentOs)
}

application {
    mainClass.set("myai.hush.kmp.desktop.MainKt")
}

kotlin {
    jvmToolchain(libs.versions.javaVersion.get().toInt())
}
