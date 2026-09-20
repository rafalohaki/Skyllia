pluginManagement {
    repositories {
        mavenLocal()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
    }
}

rootProject.name = "Skyllia"

include("api")
include("database")
include("plugin")
// NMS Version
include("nms:v1_21_R5")
include("nms:v1_21_R7")
include("nms:v26_2")
// Addons
include("addons:SkylliaOre")
include("addons:SkylliaInsights")
include("addons:SkylliaChat")
include("addons:SkylliaBank")
include("addons:SkylliaChallenge")
include("addons:SkylliaChest")
include("addons:SkylliaAcidRain")
include("addons:SkylliaIslandValue")
include("addons:SkylliaBackup")
include("addons:SkylliaMinions")
include("addons:SkylliaPerks")
include("addons:SkyBlockGameplay")
//include("addons:SkylliaExtra")
// Hook
include("hook:worldedit")
include("hook:fastasyncworldedit")
include("hook:internalworld")
include("hook:canvas")
include("hook:essentialsx")
include("hook:luckperms")
include("hook:quickshop")
include("hook:cmi")
include("hook:insights")