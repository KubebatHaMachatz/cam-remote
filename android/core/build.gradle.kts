plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Test doubles for every port live in src/testFixtures and are published to :app, so the
    // transport tests drive real commands through real fakes instead of re-inventing them.
    `java-test-fixtures`
}

// This module is deliberately a plain Kotlin/JVM library. It must never gain an Android dependency:
// the whole test strategy rests on this code being runnable in milliseconds on a desktop JVM.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
