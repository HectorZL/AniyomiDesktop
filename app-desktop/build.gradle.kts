plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose") version "1.10.3"
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

kotlin {
    jvm("desktop") {
        withJava()
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                
                implementation(projects.i18n)
                implementation(projects.i18nAniyomi)
                implementation(projects.sourceApi)
                
                implementation(kotlinx.coroutines.core)
                implementation("io.github.kdroidfilter:composemediaplayer:0.10.0")
                implementation("de.femtopedia.dex2jar:dex-translator:2.4.37")
                implementation("de.femtopedia.dex2jar:dex-tools:2.4.37")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
        jvmArgs += listOf("-noverify", "-Xverify:none")
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "AniyomiDesktop"
            packageVersion = "1.0.0"
        }
    }
}
