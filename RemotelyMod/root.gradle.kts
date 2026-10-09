import org.gradle.api.tasks.Sync

plugins {
    id("dev.deftu.gradle.multiversion-root")
}

val versionProjectPattern = Regex("""^\d+\.\d+(?:\.\d+)?(?:-(?:snapshot|pre|rc)-\d+)?-(?:fabric|neoforge|forge)$|^\d+\.\d+(?:-(?:snapshot|pre|rc)-\d+)?-(?:fabric|neoforge|forge)$""")

fun versionProjects(loader: String? = null) = subprojects
    .filter { versionProjectPattern.matches(it.name) }
    .filter { loader == null || it.name.endsWith("-$loader") }
    .sortedBy { it.name }

fun registerBuildAggregate(name: String, descriptionText: String, loader: String? = null) {
    val projects = versionProjects(loader)
    val syncName = "sync${name.removePrefix("build")}Jars"
    val syncTask = tasks.register(syncName, Sync::class.java) {
        group = "build"
        description = "Copies production jars for ${loader ?: "all loaders"} into build/versions."
        into(layout.buildDirectory.dir("versions"))
        projects.forEach { project ->
            dependsOn("${project.path}:build")
            from(project.layout.buildDirectory.dir("libs")) {
                include("Remotely-${rootProject.property("mod.version")}+${project.name}.jar")
            }
        }
    }
    tasks.register(name) {
        group = "build"
        description = descriptionText
        dependsOn(syncTask)
        doLast {
            val artifactPrefix = "Remotely-${rootProject.property("mod.version")}+"
            val expected = projects.map { project -> "$artifactPrefix${project.name}.jar" }.sorted()
            val actual = layout.buildDirectory.dir("versions").get().asFile.listFiles()
                ?.filter { file -> file.isFile && file.extension == "jar" && file.name.startsWith(artifactPrefix) }
                ?.map { file -> file.name }
                ?.sorted()
                ?: emptyList()
            if (actual != expected) error("Expected ${expected.size} version jars but found ${actual.size}: ${actual.joinToString()}")
        }
    }
}

fun registerPublishAggregate(name: String, descriptionText: String, taskName: String, loader: String? = null) {
    val projects = versionProjects(loader)
    tasks.register(name) {
        group = "publishing"
        description = descriptionText
        dependsOn(projects.map { project -> "${project.path}:$taskName" })
    }
}

registerBuildAggregate("buildAllVersions", "Builds every enabled RemotelyMod version.")
registerBuildAggregate("buildAllFabric", "Builds every enabled Fabric RemotelyMod version.", "fabric")
registerBuildAggregate("buildAllNeoForge", "Builds every enabled NeoForge RemotelyMod version.", "neoforge")

registerPublishAggregate("publishAllVersions", "Publishes every enabled RemotelyMod version.", "publishMod")
registerPublishAggregate("publishAllFabric", "Publishes every enabled Fabric RemotelyMod version.", "publishMod", "fabric")
registerPublishAggregate("publishAllNeoForge", "Publishes every enabled NeoForge RemotelyMod version.", "publishMod", "neoforge")
registerPublishAggregate("publishAllVersionsToModrinth", "Publishes every enabled RemotelyMod version to Modrinth.", "publishModrinth")
registerPublishAggregate("publishAllVersionsToCurseForge", "Publishes every enabled RemotelyMod version to CurseForge.", "publishCurseforge")

preprocess {
    strictExtraMappings.set(true)

    "26.3-fabric"(26_03, "srg") {
        "26.3-neoforge"(26_03, "srg") {
        }
        "26.2-fabric"(26_02, "srg") {
            "26.2-neoforge"(26_02, "srg") {
            }
            "26.1.2-fabric"(26_01_02, "srg") {
                "26.1-neoforge"(26_01, "srg") {
                    "1.21.11-neoforge"(1_21_11, "srg") {
                        "1.21.10-neoforge"(1_21_10, "srg") {
                            "1.21.8-neoforge"(1_21_08, "srg") {
                                "1.21.6-neoforge"(1_21_06, "srg") {
                                    "1.21.5-neoforge"(1_21_05, "srg") {
                                        "1.21.4-neoforge"(1_21_04, "srg") {
                                            "1.21.1-neoforge"(1_21_01, "srg") {
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                "1.21.11-fabric"(1_21_11, "srg") {
                    "1.21.10-fabric"(1_21_10, "srg") {
                        "1.21.8-fabric"(1_21_08, "srg") {
                            "1.21.6-fabric"(1_21_06, "srg") {
                                "1.21.5-fabric"(1_21_05, "srg") {
                                    "1.21.4-fabric"(1_21_04, "srg") {
                                        "1.21.1-fabric"(1_21_01, "srg") {
                                            "1.20.1-fabric"(1_20_1, "srg") {
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
