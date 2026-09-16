plugins {
    // AGP 9 provides Kotlin compilation to Android modules; only the pure JVM
    // domain module applies the standalone Kotlin JVM plugin below.
    alias(libs.plugins.ktlint)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
