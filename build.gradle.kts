// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// УДАЛИТЕ весь блок allprojects { repositories { ... } }
// Репозитории уже объявлены в settings.gradle.kts

tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
}

