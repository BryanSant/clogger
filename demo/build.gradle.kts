plugins {
    application
    id("com.gradleup.shadow") version "9.4.1"
}

group = "io.github.clogger"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("../build/libs/clogger-1.0.0-SNAPSHOT.jar"))
    implementation("ch.qos.logback:logback-classic:1.5.32")
}

application {
    mainClass = "io.github.clogger.demo.Main"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveFileName = "demo.jar"
}
