plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.serialization") version "2.2.21"
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
	implementation(kotlin("stdlib"))
	compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
	implementation("io.github.agrevster:pocketbase-kotlin:2.7.1")
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
	archiveClassifier.set("")
	mergeServiceFiles()
}

tasks.jar {
	enabled = false
}

tasks.build {
	dependsOn(tasks.shadowJar, "apiJar")
}

val apiJar by tasks.registering(Jar::class) {
	dependsOn(tasks.classes)
	archiveClassifier.set("api")
	from(sourceSets.main.get().output) {
		include("com/danidipp/sneakypocketbase/PocketbaseApi.class")
		include("com/danidipp/sneakypocketbase/PocketbaseProvider.class")
		include("com/danidipp/sneakypocketbase/AsyncPocketbaseEvent.class")
		include("com/danidipp/sneakypocketbase/AsyncPocketbaseEvent${'$'}Action.class")
	}
}

val verifyConsumerApi by tasks.registering {
	dependsOn(apiJar)
	doLast {
		val apiClasses = listOf(
			"com.danidipp.sneakypocketbase.PocketbaseApi",
			"com.danidipp.sneakypocketbase.PocketbaseProvider",
			"com.danidipp.sneakypocketbase.AsyncPocketbaseEvent",
			"com.danidipp.sneakypocketbase.AsyncPocketbaseEvent${'$'}Action",
		)
		val result = providers.exec {
			commandLine(
				javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath
					.file("bin/javap.exe").asFile.absolutePath,
				"-public",
				"-classpath",
				apiJar.get().archiveFile.get().asFile.absolutePath,
				*apiClasses.toTypedArray(),
			)
		}
		val publicApi = result.standardOutput.asText.get()
		val forbidden = listOf("kotlin.", "kotlinx.", "pocketbaseKotlin")
		check(forbidden.none(publicApi::contains)) {
			"Consumer API exposes an implementation type:\n$publicApi"
		}
	}
}

tasks.check {
	dependsOn(verifyConsumerApi)
}
