rootProject.name = "pandastic-core"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        maven("https://repo.essentialsx.net/releases/") {
            name = "essentialsx"
        }
        maven("https://repo.opencollab.dev/main/") {
            name = "opencollab"
        }
        // VaultAPI is published under com.github.MilkBowl, which only exists on JitPack.
        maven("https://jitpack.io") {
            name = "jitpack"
        }
    }
}

include(
    "pandastic-core-bootstrap",
    "pandastic-core-bridge-contract",
    "pandastic-core-service-api",
    "pandastic-core-annotation-processor",
    "pandastic-core-dialogs",
    "pandastic-core-config",
    "pandastic-core-database",
    "pandastic-core-services",
    "pandastic-core-util",
)
