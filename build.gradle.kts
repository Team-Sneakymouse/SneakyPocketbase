plugins {
	kotlin("jvm") version "2.0.20"
}

repositories {
	maven {
		url = uri("https://plugins.gradle.org/m2/")
	}
	maven {
		url = uri("https://repo.extendedclip.com/content/repositories/placeholderapi/")
	}
    maven {
		url = uri("https://repo.mikeprimm.com/")
	}
	mavenCentral()
	maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
	implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
	// compileOnly("me.clip:placeholderapi:2.11.5")
    // compileOnly("us.dynmap:dynmap-api:3.4-beta-3")
    // compileOnly("us.dynmap:DynmapCoreAPI:3.4")
    // implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // implementation("org.json:json:20240303")
}

tasks.jar {
	manifest {
		attributes["Main-Class"] = "com.danidipp.paperplugin.PaperPlugin"
	}

	from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

configure<JavaPluginExtension> {
	sourceSets {
		main {
			java.srcDir("src/main/kotlin")
			resources.srcDir(file("src/resources"))
		}
	}
}
