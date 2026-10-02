plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.jacobhayes.bootleg"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.jacobhayes.bootleg"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // The embedded Tailscale node (libgojni.so) is built for arm64 only.
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.useJUnitPlatform()
            // Robolectric's FileDescriptor shim reaches into JDK internals.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// The Go remote transport (remote/) as a gomobile AAR. Rebuilt only when remote/ or the toolchain
// changes; the output is cacheable, so other worktrees and clean builds reuse it.
val remoteAar = rootProject.layout.projectDirectory.file("remote/build/bootlegremote.aar")
val buildRemoteAar =
    tasks.register<Exec>("buildRemoteAar") {
        description = "Builds remote/build/bootlegremote.aar with gomobile (mise run remote:aar)"
        val remoteDir = rootProject.layout.projectDirectory.dir("remote")
        inputs
            .files(fileTree(remoteDir) { exclude("build/**") })
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .withPropertyName("remoteSources")
        inputs
            .files(rootProject.layout.projectDirectory.file("mise.toml"), rootProject.layout.projectDirectory.file("mise.lock"))
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .withPropertyName("toolchain")
        outputs.file(remoteAar).withPropertyName("aar")
        outputs.cacheIf { true }
        workingDir = rootProject.projectDir
        commandLine("mise", "run", "--quiet", "remote:aar")
    }

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    implementation(project(":core"))
    implementation(files(remoteAar).builtBy(buildRemoteAar))
    implementation(libs.zxing.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.work.runtime)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.launcher)
    // Screenshot tests: Robolectric + Roborazzi run on JUnit 4 through the vintage engine.
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    testRuntimeOnly(libs.junit.vintage)
}
