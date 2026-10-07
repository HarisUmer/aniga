import java.net.URI
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
}

val anigaLocal = Properties()
val anigaLocalFile = rootProject.file("aniga.local.properties")
if (anigaLocalFile.exists()) {
    anigaLocalFile.inputStream().use { anigaLocal.load(it) }
}

val apiBaseUrl = (
    anigaLocal.getProperty("aniga.apiBaseUrl")
        ?: System.getenv("ANIGA_API_BASE_URL")
        ?: "http://10.0.2.2:8080/"
    ).let { if (it.endsWith("/")) it else "$it/" }
val apiHost = URI(apiBaseUrl).host ?: "10.0.2.2"
val apiCleartext = apiBaseUrl.startsWith("http://")

abstract class GenerateNetworkSecurityConfig : DefaultTask() {
    @get:Input
    abstract val host: Property<String>

    @get:Input
    abstract val cleartext: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val xmlDir = outputDir.get().asFile.resolve("xml")
        xmlDir.mkdirs()
        val hostName = host.get()
        val allowCleartext = cleartext.get()
        val cleartextHosts = linkedSetOf("10.0.2.2", "localhost")
        if (allowCleartext) cleartextHosts.add(hostName)
        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("<network-security-config>")
            appendLine("""    <domain-config cleartextTrafficPermitted="true">""")
            cleartextHosts.forEach {
                appendLine("""        <domain includeSubdomains="false">${xmlText(it)}</domain>""")
            }
            appendLine("    </domain-config>")
            if (!allowCleartext && hostName != "localhost" && hostName != "10.0.2.2") {
                appendLine("""    <domain-config cleartextTrafficPermitted="false">""")
                appendLine("""        <domain includeSubdomains="false">${xmlText(hostName)}</domain>""")
                appendLine("        <trust-anchors>")
                appendLine("""            <certificates src="@raw/aniga_ca" />""")
                appendLine("""            <certificates src="system" />""")
                appendLine("        </trust-anchors>")
                appendLine("    </domain-config>")
            }
            appendLine("</network-security-config>")
        }
        xmlDir.resolve("network_security_config.xml").writeText(xml)
    }

    private fun xmlText(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

android {
    namespace = "com.example.aniga"
    compileSdk {
        version = release(37)
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.example.aniga"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation(libs.fragment)
    implementation(libs.glide)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}

androidComponents {
    onVariants { variant ->
        val taskName = "generate${variant.name.replaceFirstChar { it.uppercase() }}NetworkSecurityConfig"
        val generate = tasks.register<GenerateNetworkSecurityConfig>(taskName) {
            host.set(apiHost)
            cleartext.set(apiCleartext)
        }
        variant.sources.res?.addGeneratedSourceDirectory(generate, GenerateNetworkSecurityConfig::outputDir)
    }
}