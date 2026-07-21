plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = property("group") as String
version = property("version") as String

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("com.ticxo.modelengine:ModelEngine:R4.1.0") {
        isTransitive = false
    }
    compileOnly("io.lumine:Mythic-Dist:5.13.0-SNAPSHOT") {
        isTransitive = false
    }

    implementation("gg.moonflower:molang-compiler:3.1.1.19")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testRuntimeOnly("com.ticxo.modelengine:ModelEngine:R4.1.0") {
        isTransitive = false
    }
    testCompileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testCompileOnly("com.ticxo.modelengine:ModelEngine:R4.1.0") {
        isTransitive = false
    }
    testCompileOnly("io.lumine:Mythic-Dist:5.13.0-SNAPSHOT") {
        isTransitive = false
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("YsmModelEngineMolang-${project.version}.jar")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
