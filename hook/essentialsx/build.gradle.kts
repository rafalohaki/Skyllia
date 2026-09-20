plugins {
    id("java")
}
group = "fr.euphyllia.skyllia.hook.essentialsx"
version = "2.3"


repositories {
    // Mirror Central — repo.maven.apache.org/repo1 mają wspólny rate-limit
    // (429 dla tego IP), patrz allprojects.repositories w root build.
    maven("https://maven.aliyun.com/repository/public")
    maven {
        name = "EssentialsX"
        url = uri("https://repo.essentialsx.net/releases/")
    }
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT") { isTransitive = false }
    compileOnly("net.kyori:adventure-text-minimessage:4.25.0")
    compileOnly(project(":api"))

    compileOnly("net.essentialsx:EssentialsX:2.21.2") { isTransitive = false }
    compileOnly("net.essentialsx:EssentialsXSpawn:2.21.2") { isTransitive = false }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
