plugins {
    `java-library`
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

// Configuration that the standalone CLI fat jar pulls from. Keeps logback
// out of the regular library jar (which stays compileOnly so library
// consumers control their own logback version) while still letting
// shadowJar bundle everything the standalone tool needs at runtime.
val cliRuntime by configurations.creating

dependencies {
    compileOnly("ch.qos.logback:logback-classic:1.5.32")

    cliRuntime("ch.qos.logback:logback-classic:1.5.32")

    testImplementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

application {
    mainClass = "io.github.clogger.Main"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// Library jar stays untouched — no Main-Class, no bundled deps. Library
// consumers depend on this artifact and provide their own logback.
tasks.named<Jar>("jar") {
    manifest {
        // Intentionally no Main-Class here.
    }
}

// Standalone fat jar with everything bundled. Run via `java -jar
// build/libs/clogger-cli.jar` or pipe stdin into it.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName = "clogger-cli"
    archiveClassifier = ""
    archiveVersion = ""
    configurations = listOf(cliRuntime)
    manifest {
        attributes["Main-Class"] = "io.github.clogger.Main"
    }
}

// JavaExec for `./gradlew run` needs stdin connected and logback on the
// runtime classpath. Add cliRuntime to the run task's classpath.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    classpath += cliRuntime
}
