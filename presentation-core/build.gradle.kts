plugins {
    id("mihon.library")
    id("mihon.library.compose")
    kotlin("android")
}

android {
    namespace = "tachiyomi.presentation.core"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

dependencies {
    api(projects.core.common)
    api(projects.i18n)

    // Compose
    implementation(composeCatalog.activity)
    implementation(composeCatalog.foundation)
    implementation(composeCatalog.material3.core)
    implementation(composeCatalog.material.icons)
    implementation(composeCatalog.animation)
    implementation(composeCatalog.animation.graphics)
    debugImplementation(composeCatalog.ui.tooling)
    implementation(composeCatalog.ui.tooling.preview)
    implementation(composeCatalog.ui.util)

    implementation(androidx.paging.runtime)
    implementation(androidx.paging.compose)
    implementation(kotlinx.immutables)
}
