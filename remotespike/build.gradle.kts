// Throwaway phase 3 feasibility spike (see remote/SPIKE.md). Only included in the build when
// remote/build/bootlegremote.aar exists (`mise run remote:aar`).
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.jacobhayes.bootleg.remotespike"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.jacobhayes.bootleg.remotespike"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    implementation(files(rootProject.file("remote/build/bootlegremote.aar")))
}
