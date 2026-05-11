plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.6"
    id("net.linguica.maven-settings") version "0.5"
}

group = "it.battlejar.commander"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    maven {
        url = uri("https://maven.pkg.github.com/mateusz-dykszak/battlejar-client")
        name = "github-battlejar-client"
    }
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("it.battlejar:battlejar-api:0.2.3")
    implementation("it.battlejar:battlejar-client:0.2.3")
    implementation("it.battlejar:battlejar-math:0.2.3")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3")

    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")

    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass = "it.battlejar.commander.Main"
}

tasks.shadowJar {
    archiveFileName = "commander.jar"
    destinationDirectory = layout.buildDirectory
}
