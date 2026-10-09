plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("common") {
            id = "remotelymod.common"
            implementationClass = "redxax.remotelymod.buildlogic.RemotelyModCommonPlugin"
        }
        register("fabricLoom") {
            id = "remotelymod.fabric-loom"
            implementationClass = "redxax.remotelymod.buildlogic.RemotelyModFabricLoomPlugin"
        }
        register("fabricDrop") {
            id = "remotelymod.fabric-drop"
            implementationClass = "redxax.remotelymod.buildlogic.RemotelyModFabricDropPlugin"
        }
        register("neoForgeModDev") {
            id = "remotelymod.neoforge-moddev"
            implementationClass = "redxax.remotelymod.buildlogic.RemotelyModNeoForgeModDevPlugin"
        }
        register("forgeLegacy") {
            id = "remotelymod.forge-legacy"
            implementationClass = "redxax.remotelymod.buildlogic.RemotelyModForgeLegacyPlugin"
        }
    }
}

dependencies {
    compileOnly("com.github.johnrengelman:shadow:8.1.1")
    implementation("com.hypherionmc.modutils:modpublisher:2.1.8")
    implementation("net.neoforged:moddev-gradle:2.0.148")
}
