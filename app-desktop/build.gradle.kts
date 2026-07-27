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
                implementation(projects.sourceApi) {
                    exclude(group = "com.squareup.logcat", module = "logcat")
                }
                
                implementation(kotlinx.coroutines.core)
                implementation("io.github.kdroidfilter:composemediaplayer:0.10.0")
                implementation("de.femtopedia.dex2jar:dex-translator:2.4.37")
                implementation("de.femtopedia.dex2jar:dex-tools:2.4.37")

                // JavaFX WebEngine — real WebKit engine for desktop WebView bridge
                // Allows extensions that use android.webkit.WebView to execute
                // real JavaScript on the desktop JVM.
                val javafxVersion = "21.0.2"
                val javafxPlatform = "win" // Windows only for now
                implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
                implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
                implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")
                implementation("org.openjfx:javafx-web:$javafxVersion:$javafxPlatform")
                implementation("org.openjfx:javafx-media:$javafxVersion:$javafxPlatform")
                implementation("org.openjfx:javafx-swing:$javafxVersion:$javafxPlatform")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
                implementation(kotlinx.coroutines.test)
                implementation(libs.kotest.property)
            }
        }
    }
}

tasks.named<org.gradle.api.tasks.testing.Test>("desktopTest") {
    systemProperty("kotest.proptest.default.iteration.count", "100")
}

// JavaFX WebKit resolves its platform classes as named modules (it looks up
// sibling module jars like javafx-media on the module layer at runtime).
// Compose's desktop run task puts every dependency on the plain classpath,
// which leaves WebKit unable to find com.sun.media.jfxmedia.MediaManager
// because that class only exists inside the javafx.media *module*, not as a
// plain classpath jar. Having the same jars on both -cp and --module-path
// also breaks module resolution ("named module ... on both class path and
// module path"), so we must remove them from the classpath before adding
// them back exclusively via --module-path.
tasks.withType<org.gradle.api.tasks.JavaExec>().configureEach {
    doFirst {
        val javafxJars = classpath.files
            .filter { it.name.matches(Regex("javafx-(base|graphics|controls|media|web|swing)-.*-win\\.jar")) }
        if (javafxJars.isNotEmpty()) {
            classpath = classpath.minus(project.files(javafxJars))
            jvmArgs(
                "--module-path",
                javafxJars.joinToString(File.pathSeparator) { it.absolutePath },
                "--add-modules",
                "javafx.base,javafx.graphics,javafx.controls,javafx.media,javafx.web,javafx.swing",
            )
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
        jvmArgs += listOf("-noverify", "-Xverify:none", "--add-modules=jdk.httpserver")
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            modules("jdk.httpserver")
            packageName = "AniyomiDesktop"
            packageVersion = "1.0.0"
        }
    }
}
