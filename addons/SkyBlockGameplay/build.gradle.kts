plugins {
    id("java")
}

group = "org.rafalohaki.wpmecore.addons.skyblock"

repositories {
    exclusiveContent {
        forRepository { maven("https://repo.papermc.io/repository/maven-public/") }
        filter { includeGroup("dev.folia") }
    }
}

dependencies {
    compileOnly("dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly(project(":api"))
    compileOnly(files("libs/wpmecore.jar", "libs/EvenMoreFish-2.4.5.jar", "libs/PlaceholderAPI-2.12.3.jar"))

    testImplementation("dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7.1")
    testImplementation(project(":api"))
    testImplementation(files("libs/wpmecore.jar", "libs/EvenMoreFish-2.4.5.jar", "libs/PlaceholderAPI-2.12.3.jar"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("net.jqwik:jqwik:1.9.3")
    testImplementation("org.mockito:mockito-junit-jupiter:5.15.2")
    testImplementation("org.objenesis:objenesis:3.4")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.1.2:4.113.2")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.xerial:sqlite-jdbc:3.53.2.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
    compileTestJava {
        options.encoding = "UTF-8"
    }
    test {
        useJUnitPlatform {
            excludeTags("mockbukkit")
        }
        // Te same wykluczenia co mockbukkit.test.exclude w maven — testy wymagające
        // rejestru Papera, których emulator MockBukkit nie udźwignie na 26.2.
        listOf("IslandCenterMenuTest", "OneBlockMilestoneMenuTest", "OneBlockServiceTest",
                "SkyBlockSettingsTest", "SeasonAdminMenuLayoutTest", "SeasonAdminMenuNestedWindowsTest",
                "SeasonCloseConfirmGuardTest", "InventoryOutboxRecoveryTest", "InventoryOutboxTest",
                "SkyBlockTopRewardCoordinatorTest", "OneBlockContentLoaderTest", "UseItemRegistrationContractTest",
                "UiLayoutTest", "CosmeticPlayerMenuTest", "ChequeCommandsOverflowTest",
                "CollectibleRecipeGuardTest", "CustomItemMaterialGuardTest", "FireballCombatListenerTest",
                "KitPresentationCatalogTest", "PriceServiceImplTest", "RandomTeleportSettingsTest",
                "WorldRtpRangeTest", "HubCratesTest", "CratePreviewMenuLayoutTest", "ModelMenuLayoutTest",
                "CustomFireballListenerTest", "FireballItemBrandingTest", "ModelCatalogContractTest",
                "CustomItemsContractTest", "ZMenuCustomItemLoaderTest", "KitPresetCatalogTest",
                "KitServiceImplTest", "MockBukkitProbeTest", "AddonBootstrapTest", "InventoryMenuServiceTest",
                "SimpleMenuTest", "BukkitClientAssetServiceTest", "QuantityMathTest", "RecipeIntegrationTest",
                "ServerShopTest", "CrateKeyListenerTest", "RankPanelCatalogTest", "RankPanelViewTest",
                "ToolsCommandsCatalogTest", "AdminPanelControllerTest", "NickNameConcurrencyTest",
                "NickNameEntitlementTest", "TagServiceImplTest", "InventoryGrantPlannerTest")
                .forEach { name -> filter.excludeTestsMatching("*" + name + "*") }
    }

}
