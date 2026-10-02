plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.serialization") version "2.3.21"
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
	compileOnly("io.papermc.paper:paper-api:26.2.build.117-stable")
	implementation("io.github.agrevster:pocketbase-kotlin:2.7.1")
	compileOnly(files("libs/MagicSpells-4.0-Beta-13.jar"))
	testImplementation(kotlin("test-junit5"))
	testImplementation("org.junit.jupiter:junit-jupiter:5.14.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.1")
	testImplementation("io.papermc.paper:paper-api:26.2.build.117-stable")
}

tasks.test { useJUnitPlatform() }

configure<JavaPluginExtension> {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
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
		include("com/danidipp/sneakypocketbase/PocketbaseLifecycleSnapshot*.class")
		include("com/danidipp/sneakypocketbase/AsyncPocketbaseLifecycleEvent.class")
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
			"com.danidipp.sneakypocketbase.PocketbaseLifecycleSnapshot",
			"com.danidipp.sneakypocketbase.PocketbaseLifecycleSnapshot${'$'}ApiState",
			"com.danidipp.sneakypocketbase.PocketbaseLifecycleSnapshot${'$'}TransportState",
			"com.danidipp.sneakypocketbase.PocketbaseLifecycleSnapshot${'$'}SubscriptionState",
			"com.danidipp.sneakypocketbase.PocketbaseLifecycleSnapshot${'$'}CollectionStatus",
			"com.danidipp.sneakypocketbase.AsyncPocketbaseLifecycleEvent",
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
		val forbidden = listOf("kotlin.", "kotlinx.", "io.ktor.", "pocketbaseKotlin")
		check(forbidden.none(publicApi::contains)) {
			"Consumer API exposes an implementation type:\n$publicApi"
		}
	}
}

tasks.check {
	dependsOn(verifyConsumerApi)
}
