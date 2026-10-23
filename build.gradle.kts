plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

group = "dev.egor"
version = "2.1.0"
val mcVersion = "26.3"

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

dependencies {
    // Uses server internals: one build per Minecraft version.
    paperweight.paperDevBundle("26.3.build.214-stable")
}

tasks {
    processResources {
        val props = mapOf("version" to version, "mcVersion" to mcVersion)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }
    jar { archiveFileName = "EgorsFakePlayer-${version}-mc${mcVersion}.jar" }
}
