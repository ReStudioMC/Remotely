import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    `java-library`
    `maven-publish`
}

group = "dev.restudio"
version = property("remotely.version").toString()

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val browserJar by tasks.registering(Jar::class) {
    archiveClassifier.set("browser")
    from(sourceSets.main.get().output)
}

val browserElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(browserElements.name, browserJar)
}

publishing {
    publications {
        create<MavenPublication>("networkCore") {
            from(components["java"])
            artifact(browserJar)
            artifactId = project.name
        }
    }
}
