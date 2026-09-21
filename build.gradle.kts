plugins {
    `java-library`
    `maven-publish`
}

group = "com.natesoftware"
version = "1.1.0"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = "rift-border"
                description = "A volumetric circular shrinking border for Paper servers."
                url = "https://github.com/natesoftware/rift-border"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://github.com/natesoftware/rift-border/blob/main/LICENSE"
                    }
                }
            }
        }
    }
}
