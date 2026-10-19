// 根构建脚本：只声明插件版本，具体配置在各模块内
plugins {
    id("com.android.application") version "8.5.2" apply false
    kotlin("android") version "2.0.21" apply false
    // Compose 编译器插件（Kotlin 2.0 起独立）
    kotlin("plugin.compose") version "2.0.21" apply false
    // KSP：Room 编译期代码生成
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}
