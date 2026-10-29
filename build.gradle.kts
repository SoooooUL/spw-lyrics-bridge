import com.xuncorp.spw.workshop.gradle.PluginPermission
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    kotlin("jvm") version "2.4.20"
    kotlin("kapt") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

group = "dev.icesheep.spw"
version = "1.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)

    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        // 无需 jvm-default：本插件只实现宿主接口并继承其默认方法，
        // 默认实现的编译形态由 API 模块自身决定
    }
}

dependencies {
    // Kotlin 标准库与 Workshop API 均由宿主（Salt Player）提供，
    // 用 compileOnly 避免被打进 .spmod 分发包；pf4j 是 API 的传递依赖，无需另声明
    compileOnly(kotlin("stdlib"))
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev22")
    // kapt 处理 @Extension 注解，生成 PF4J 扩展索引
    kapt("com.github.Moriafly:spw-workshop-api:0.1.0-dev22")
}

// 插件元数据（写入 Manifest）
spmod {
    PluginClass = "dev.icesheep.spw.lyricsbridge.MainPlugin"
    PluginId = "dev.icesheep.spw.lyricsbridge"
    PluginVersion = "1.1.0"
    PluginProvider = "ice_sheep"
    PluginName = "歌词桥"
    PluginDescription = "导出当前歌曲歌词时间轴为本地 JSON 文件，供外部组件读取"
    PluginHasConfig = true
    // 仅需读取曲库（当前歌曲元数据）；不申请写库与 Hook 权限
    PluginPermissions = listOf(PluginPermission.LIBRARY_READ)
}
