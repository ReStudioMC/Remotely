rootProject.name = "Remotely"

include(":NetworkCore")
project(":NetworkCore").projectDir = file("NetworkCore")

include(":RemotelyWeb")
project(":RemotelyWeb").projectDir = file("RemotelyWeb")

apply(from = file("../Rebase/gradle/restudio-workspace.settings.gradle"))

if (extra["reStudioSourceDependencies"] as Boolean) {
    includeBuild("../ReScreen") {
        dependencySubstitution {
            substitute(module("dev.restudio:rescreen")).using(project(":"))
        }
    }

    includeBuild("../Remodel") {
        dependencySubstitution {
            substitute(module("dev.restudio:remodel")).using(project(":"))
        }
    }

    includeBuild("../Rebase") {
        dependencySubstitution {
            substitute(module("dev.restudio:rebase")).using(project(":"))
        }
    }
}

includeBuild("../ReSync") {
    dependencySubstitution {
        substitute(module("restudio.resync:ReSyncCore")).using(project(":ReSyncCore"))
    }
}
