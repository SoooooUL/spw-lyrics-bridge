// Workshop Gradle 插件经 JitPack 多模块发布，groupId 与 Gradle 插件标记默认坐标不一致，
// 必须保留 useModule(...) 映射，否则解析到 com.xuncorp.spw.workshop:...gradle.plugin 而失败
pluginManagement {
    repositories {
        maven("https://jitpack.io")
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.xuncorp.spw.workshop") {
                useModule(
                    "com.github.Moriafly.spw-workshop-api:spw-workshop-gradle-plugin:${requested.version}"
                )
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "spw-lyrics-bridge"
