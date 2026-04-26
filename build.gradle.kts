plugins {
    `java-library`
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
    compileOnly("ch.qos.logback:logback-classic:1.5.32")
    implementation("io.github.kusoroadeolu:clique-core:4.0.1")

    testImplementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
