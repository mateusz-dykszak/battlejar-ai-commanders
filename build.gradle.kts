plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.6"
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
        credentials {
            username = findProperty("gpr.user") as String? ?: System.getenv("USERNAME")
            password = findProperty("gpr.key") as String? ?: System.getenv("TOKEN")
        }
    }
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("it.battlejar:battlejar-api:0.2.1-SNAPSHOT")
    implementation("it.battlejar:battlejar-client:0.2.1-SNAPSHOT")
    implementation("it.battlejar:battlejar-math:0.2.1-SNAPSHOT")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3")

    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")

    implementation("ch.qos.logback:logback-classic:1.5.18")
}

application {
    mainClass = "it.battlejar.commander.Main"
}
