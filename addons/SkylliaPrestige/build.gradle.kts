plugins {
    id("java")
}

group = "pl.b2t.skylliaprestige"

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(project(":api"))
    compileOnly(project(":plugin"))
    compileOnly(project(":database"))
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
    processResources {
        filesMatching("paper-plugin.yml") {
            expand("version" to project.version)
        }
    }
}
