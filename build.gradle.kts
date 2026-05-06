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

sourceSets {
    main {
        java {
            srcDir("src/main/java")
            srcDir("battlejar-client-sources/api")
            srcDir("battlejar-client-sources/client")
            srcDir("battlejar-client-sources/math")
        }
    }
}

dependencies {
    implementation("it.battlejar:battlejar-api:0.2.2")
    implementation("it.battlejar:battlejar-client:0.2.2")
    implementation("it.battlejar:battlejar-math:0.2.2")
    // Add common dependencies if needed, but client might have them
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3")

    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")

    implementation("ch.qos.logback:logback-classic:1.5.18")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf(
        "--add-opens", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-opens", "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
        "--add-opens", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-opens", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-opens", "jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
        "--add-opens", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-opens", "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
        "--add-opens", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-opens", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    ))
    options.release.set(25)
}

application {
    mainClass = "it.battlejar.commander.Main"
}

tasks.shadowJar {
    archiveFileName = "commander.jar"
    destinationDirectory = layout.buildDirectory
}
