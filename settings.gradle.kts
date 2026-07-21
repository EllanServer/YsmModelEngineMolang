pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://mvn.lumine.io/repository/maven-public/") {
            metadataSources {
                mavenPom()
                artifact()
            }
        }
        maven("https://maven.blamejared.com/")
    }
}

rootProject.name = "ysm-modelengine-molang"
