import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.daemon.common.isDaemonEnabled
import java.nio.file.Path

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

group = "org.freefjay.localshare"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    // Note, if you develop a library, you should use compose.desktop.common.
    // compose.desktop.currentOs should be used in launcher-sourceSet
    // (in a separate module for demo project and in testMain).
    // With compose.desktop.common you will also lose @Preview functionality
    implementation(compose.desktop.currentOs)
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-call-logging:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-gson:2.3.12")
    implementation("io.ktor:ktor-server-partial-content:2.3.12")
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("ch.qos.logback:logback-classic:1.5.3")
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    implementation("com.darkrockstudios:mpfilepicker:3.1.0")
    implementation("com.google.zxing:core:3.5.1")
    // https://mvnrepository.com/artifact/org.jmdns/jmdns
    implementation("org.jmdns:jmdns:3.5.12")

}

compose.desktop {
    application {
        mainClass = "com.freefjay.localshare.desktop.MainKt"

        buildTypes.release.proguard {
            configurationFiles.from(project.file("rules.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "localshare-desktop"
            packageVersion = "1.0.1"
            modules("java.instrument", "java.management", "java.sql", "jdk.unsupported", "java.naming", "jdk.charsets")
            windows {
                shortcut = true
            }
        }
    }
}
