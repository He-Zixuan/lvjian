// 滤见 —— 微信群消息过滤清单 APP
// Gradle 工程入口：声明仓库与包含的模块
pluginManagement {
    repositories {
        // 国内镜像优先（校园网直连 google()/central 很慢），官方仓库兜底
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "lvjian"
include(":app")
