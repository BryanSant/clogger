plugins {
    application
    id("com.gradleup.shadow") version "9.4.1"
}

group = "io.github.clilogger"
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
    implementation(files("../build/libs/clilogger-1.0.0-SNAPSHOT.jar"))
    implementation("ch.qos.logback:logback-classic:1.5.32")
    implementation("io.github.kusoroadeolu:clique-core:4.0.1")
}

application {
    mainClass = "io.github.clilogger.demo.Main"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveFileName = "demo.jar"
}
