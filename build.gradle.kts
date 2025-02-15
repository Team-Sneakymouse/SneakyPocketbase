plugins {
	kotlin("jvm") version "2.1.0"
	kotlin("plugin.serialization") version "2.1.0"
	id("com.gradleup.shadow") version "8.3.6"
}

repositories {
	mavenCentral()
	maven {
		url = uri("https://plugins.gradle.org/m2/")
	}
	maven {
		name = "papermc"
		url = uri("https://repo.papermc.io/repository/maven-public/")
	}
}

dependencies {
	implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
	compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
	implementation("io.github.agrevster:pocketbase-kotlin:2.6.3")
	compileOnly(files("libs/MagicSpells-4.0-Beta-13.jar"))
}

configure<JavaPluginExtension> {
	sourceSets {
		main {
			java.srcDir("src/main/kotlin")
			resources.srcDir(file("src/resources"))
		}
	}
}

tasks.shadowJar {
	archiveClassifier.set("") // Ensures this is the main JAR
	mergeServiceFiles()
	relocate("kotlin", "com.danidipp.sneakypocketbase.kotlin") // Prevents conflicts
}

tasks.jar {
	manifest {
		attributes["Main-Class"] = "com.danidipp.sneakypocketbase.SneakyPocketbase"
	}
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE

	from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

tasks.build {
//	dependsOn(tasks.shadowJar)
}