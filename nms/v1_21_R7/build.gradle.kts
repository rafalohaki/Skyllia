plugins {
    id("java")
    id("io.papermc.paperweight.userdev")
}


paperweight {
    paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
}

repositories {
    // dev.folia:dev-bundle jest tylko na repo.papermc.io — bez exclusive
    // rewalidacja SNAPSHOT odpytuje też mirror Central i łapie 429.
    exclusiveContent {
        forRepository { maven("https://repo.papermc.io/repository/maven-public/") }
        filter { includeGroup("dev.folia") }
    }
}

dependencies {
    paperweight.foliaDevBundle("1.21.11-R0.1-SNAPSHOT")
    compileOnly(project(":nms:v1_21_R5"))
    compileOnly(project(":api"))

}


tasks {
    assemble {
        dependsOn(reobfJar)
    }
    compileJava {
        options.encoding = "UTF-8"
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

configurations.all {
    exclude(group = "net.kyori", module = "adventure-text-serializer-ansi")
}
