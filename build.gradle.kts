import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import io.izzel.taboolib.gradle.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21

plugins {
    java
    id("io.izzel.taboolib") version "2.0.38"
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
}

taboolib {
    env {
        install(Basic)
        install(CommandHelper)
        install(Bukkit)
        install(BukkitHook)
        install(BukkitUI)
        install(BukkitNMSItemTag)
//        install(Database)
        install(I18n)
//        install(Kether)
        install(Metrics)
    }
    description {
        name = "SnowyGems"
        desc("A powerful gem plugin")
        contributors {
            name("mincHR546")
            name("SnowyMC")
        }
        links {
            name("www.snowymc.top")
        }
    }
    version { taboolib = "6.3.0-75b18a2" }
}

repositories {
    mavenCentral()
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
//    compileOnly("ink.ptms.core:v12107:12107:mapped")
//    compileOnly("ink.ptms.core:v12107:12107:universal")
    compileOnly("ink.ptms.core:v260100:260100-minimize")
    compileOnly("ink.ptms.core:v260100:260100")
    compileOnly(kotlin("stdlib"))
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly(fileTree("libs"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JVM_21)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
