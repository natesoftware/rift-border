// Lets Gradle auto-provision the Java 21 toolchain build.gradle.kts asks for, so a clone builds on a machine with any JDK.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "rift-border"
