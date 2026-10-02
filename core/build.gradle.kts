plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    implementation(libs.serialization.json)
    implementation(libs.jsoup)
    implementation(libs.coroutines.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.property)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    // L3 (live network) tests are tagged "live" and run only with -Plive.
    val live = project.hasProperty("live")
    useJUnitPlatform { if (live) includeTags("live") else excludeTags("live") }
    // -Precord re-records catalog fixtures from the live sites.
    systemProperty("bootleg.record", project.hasProperty("record").toString())
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        // check:catalog runs with -q; live tests print only WARN lines (tolerated transient upstream failures).
        if (live) {
            quiet {
                events("failed", "standard_out")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
