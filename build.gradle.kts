// AGP 9 compiles Kotlin itself (built-in Kotlin) and depends on an older Kotlin Gradle plugin; this pins the one
// the project uses (gradle/libs.versions.toml "kotlin"). Do not apply org.jetbrains.kotlin.android.
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21") }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
