plugins {
    id("com.android.application") version "9.3.2" apply false
    // AGP 9 has built-in Kotlin support; the standalone org.jetbrains.kotlin.android
    // plugin is no longer applied (removing it is required — AGP 9 errors otherwise).
    // See https://developer.android.com/build/migrate-to-built-in-kotlin
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}
