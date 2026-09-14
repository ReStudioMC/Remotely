import groovy.lang.MissingPropertyException
import java.util.Properties

pluginManagement {
    includeBuild("build-logic")

    repositories {
        // Repositories
        maven("https://maven.deftu.dev/releases")
        maven("https://maven.fabricmc.net")
        maven("https://maven.architectury.dev/")
        maven("https://maven.minecraftforge.net")
        maven("https://repo.essential.gg/repository/maven-public")
        maven("https://jitpack.io/")
        maven("https://maven.firstdark.dev/releases/")

        // Snapshots
        maven("https://maven.deftu.dev/snapshots")
        maven("https://s01.oss.sonatype.org/content/groups/public/")
        mavenLocal()

        // Default repositories
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        kotlin("jvm") version("2.2.10")
        id("dev.deftu.gradle.multiversion-root") version("2.73.0")
        id("com.hypherionmc.modutils.modpublisher") version "2.1.8"
        id("net.neoforged.moddev") version "2.0.141"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version ("0.8.+")
}

val dropFabricProjectPattern = Regex("""^\d{2,}\.\d+(?:\.\d+)?(?:-(?:snapshot|pre|rc)-\d+)?-fabric$""")
val remotelyAppProperties = Properties().apply {
    file("../gradle.properties").inputStream().use(::load)
}
val remotelyVersion = providers.gradleProperty("remotely.version").orNull ?: remotelyAppProperties.getProperty("remotely.version")
    ?: throw MissingPropertyException("remotely.version has not been set.")

gradle.beforeProject {
    extensions.extraProperties["remotely.version"] = remotelyVersion
    extensions.extraProperties["mod.version"] = remotelyVersion

    if (dropFabricProjectPattern.matches(name)) {
        extensions.extraProperties["fabric.loom.disableObfuscation"] = "true"
        extensions.extraProperties["dgt.loom.mappings.use"] = "false"
        if (findProperty("dgt.fabric.loader.version") == null && findProperty("fabric.loader.version") == null) {
            extensions.extraProperties["dgt.fabric.loader.version"] = "0.18.4"
        }
    }
}

rootProject.name = "RemotelyMod"

apply(from = file("../../Rebase/gradle/restudio-workspace.settings.gradle"))

includeBuild("../../ReScreen") {
    dependencySubstitution {
        substitute(module("dev.restudio:rescreen")).using(project(":"))
    }
}

includeBuild("../../Remodel") {
    dependencySubstitution {
        substitute(module("dev.restudio:remodel")).using(project(":"))
    }
}

includeBuild("../../Rebase") {
    dependencySubstitution {
        substitute(module("dev.restudio:rebase")).using(project(":"))
    }
}

includeBuild("../../Recast")

includeBuild("../../ReSync") {
    dependencySubstitution {
        substitute(module("restudio.resync:ReSyncCore")).using(project(":ReSyncCore"))
    }
}

includeBuild("..") {
    name = "RemotelyApp"
}

extra["mod.name"]?.toString()
    ?: throw MissingPropertyException("mod.name has not been set.")
rootProject.buildFileName = "root.gradle.kts"

listOf(
//    "1.8.9-forge",
//    "1.8.9-fabric",
//
//    "1.12.2-forge",
//    "1.12.2-fabric",
//
//    "1.16.5-forge",
//    "1.16.5-fabric",

//    "1.17.1-forge",
//    "1.17.1-fabric",
//
//    "1.18.2-forge",
//    "1.18.2-fabric",
//
//    "1.19.2-forge",
//    "1.19.2-fabric",
//
//    "1.19.4-forge",
//    "1.19.4-fabric",
//
//    "1.20.1-forge",
    "1.20.1-fabric",
//
//    "1.20.4-forge",
//    "1.20.4-neoforge",
//    "1.20.4-fabric",
//
//    "1.20.6-neoforge",
//    "1.20.6-fabric",
//
    "1.21.1-neoforge",
    "1.21.1-fabric",
//
//    "1.21.2-neoforge",
//    "1.21.2-fabric",
//
//    "1.21.3-neoforge",
//    "1.21.3-fabric",
//
    "1.21.4-neoforge",
    "1.21.4-fabric",

    "1.21.5-neoforge",
    "1.21.5-fabric",

    "1.21.6-neoforge",
    "1.21.6-fabric",

//    "1.21.7-neoforge",
//    "1.21.7-fabric",

//    "1.21.8-neoforge",
    "1.21.8-neoforge",
    "1.21.8-fabric",

//    "1.21.9-neoforge",
//    "1.21.9-fabric",

//    "1.21.10-neoforge",
    "1.21.10-neoforge",
    "1.21.10-fabric",

    "1.21.11-neoforge",
    "1.21.11-fabric",

    "26.3-rc-3-fabric",
    "26.2-fabric",
    "26.2-neoforge",
    "26.1-neoforge",
    "26.1.2-fabric",
).forEach { version ->
    include(":$version")
    project(":$version").apply {
        projectDir = file("versions/$version")
        buildFileName = "../../build.gradle.kts"
    }
}
