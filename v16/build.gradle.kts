import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.nio.file.Files

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.ksp)
}

group = "com.monta.ocpp.emulator"
version = "1.0.1"

kotlin {
    jvm()
    sourceSets {
        jvmTest.dependencies {
            implementation(libs.kotlin.test.junit5)
            implementation(libs.junit.jupiter)
        }
        jvmMain.dependencies {
            implementation(project(":common"))

            implementation(compose.desktop.currentOs)

            // Material Icons
            implementation(compose.materialIconsExtended)

            // OCPP Libs
            implementation(libs.ocpp.core)
            implementation(libs.ocpp.v16)

            // Coroutines
            implementation(project.dependencies.platform(libs.kotlinx.coroutines.bom))
            implementation(libs.bundles.coroutines)

            // Websocket Client
            implementation(project.dependencies.platform(libs.ktor.bom))
            implementation(libs.bundles.ktor.client)

            // Jackson
            implementation(project.dependencies.platform(libs.jackson.bom))
            implementation(libs.bundles.jackson)

            // QR Code Library
            implementation(libs.qrcodegen)

            // Logging
            implementation(libs.bundles.logging)

            // Sentry (Crash reporting)
            implementation(project.dependencies.platform(libs.sentry.bom))
            implementation(libs.bundles.sentry)

            // Dependency Injection
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.bundles.koin)

            // SQL Database
            implementation(project.dependencies.platform(libs.exposed.bom))
            implementation(libs.bundles.exposed)
        }
    }
}

dependencies {
    add("kspJvm", libs.koin.ksp.compiler)
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

ktlint {
    filter {
        exclude("**/generated/**")
        include("**/kotlin/**")
    }
}

compose.desktop {
    application {

        buildTypes.release.proguard {
            configurationFiles.from(rootProject.file("compose-desktop.pro"))
        }

        mainClass = "com.monta.ocpp.emulator.AppKt"

        nativeDistributions {
            modules("java.naming", "java.instrument", "java.management", "java.sql", "jdk.unsupported")
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Exe)

            packageName = "EVCharge-OCPP-Emulator"
            packageVersion = "$version"
            description = "EVCharge desktop charging station emulator for OCPP 1.6J"
            vendor = "EVCharge"
            licenseFile.set(rootProject.file("LICENSE"))

            // JVM arguments, pass build-time properties here
            jvmArgs += listOfNotNull(
                System.getenv("SENTRY_DSN")?.let { "-Dsentry.dsn=$it" },
            )

            macOS {
                iconFile.set(project.file("src/jvmMain/resources/icon.icns"))
                bundleID = "mobi.evcharge.ocpp.emulator"
            }
            linux {
                debMaintainer = "support@evcharge.mobi"
                iconFile.set(project.file("src/jvmMain/resources/icon.png"))
            }
            windows {
                iconFile.set(project.file("src/jvmMain/resources/icon.ico"))
                menu = true
                shortcut = true
                upgradeUuid = "bc2110c2-d89c-4cd1-9ea2-bd5db0041678"
            }
        }
    }
}

// A running JVM keeps JAR offsets cached. Rebuilding jvmJar in place used to
// invalidate those offsets and crash Compose when it loaded another SVG/class.
// Keep each :v16:run process independent of build/ (including concurrent clean).
val runSnapshot = objects.directoryProperty()
val cleanupRunSnapshot = tasks.register("cleanupRunSnapshot") {
    doLast {
        runSnapshot.orNull?.asFile?.let { snapshot ->
            val temporaryRoot = file(System.getProperty("java.io.tmpdir")).canonicalFile
            require(snapshot.canonicalFile.parentFile == temporaryRoot && snapshot.name.startsWith("ocpp-emulator-run-"))
            delete(snapshot)
        }
    }
}

tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    doFirst {
        val snapshot = Files.createTempDirectory("ocpp-emulator-run-").toFile()
        runSnapshot.set(snapshot)
        val runtimeFiles = classpath.files.mapIndexed { index, source ->
            val destination = snapshot.resolve("$index-${source.name}")
            if (source.isDirectory) {
                check(source.copyRecursively(destination)) { "Could not snapshot runtime directory" }
            } else {
                source.copyTo(destination)
            }
            destination
        }
        classpath = files(runtimeFiles)
        logger.lifecycle("Emulator runtime snapshot: $snapshot")
    }
    finalizedBy(cleanupRunSnapshot)
}
