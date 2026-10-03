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
    // L3 (live network) tests are tagged "live" and run only with -Plive; the per-feed timing report
    // is tagged "timing" and runs only with -Ptiming.
    val live = project.hasProperty("live")
    val timing = project.hasProperty("timing")
    useJUnitPlatform {
        when {
            timing -> includeTags("timing")
            live -> includeTags("live")
            else -> excludeTags("live", "timing")
        }
    }
    // -Precord re-records catalog fixtures from the live sites.
    systemProperty("bootleg.record", project.hasProperty("record").toString())
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        // check:catalog runs with -q; live tests print only WARN lines (tolerated transient upstream failures).
        if (live || timing) {
            quiet {
                events("failed", "standard_out")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
