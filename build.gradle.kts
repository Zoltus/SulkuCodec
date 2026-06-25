plugins {
    `java-library`
    `maven-publish`
}

group = "fi.sulku.hytale"
version = "1.0-SNAPSHOT"

val hytaleVersion = "0.6.0-pre.3"

repositories {
    mavenCentral()
    maven("https://maven.hytale.com/pre-release")
}

dependencies {
    compileOnly("com.hypixel.hytale:Server:${hytaleVersion}")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
    withJavadocJar()
}

tasks.jar {
    if (project.hasProperty("jarOut")) {
        destinationDirectory.set(file(project.property("jarOut") as String))
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}