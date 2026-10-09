import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.serialization") version "2.3.21"
	id("com.gradleup.shadow") version "8.3.6"
	`maven-publish`
}

group = "io.github.team-sneakymouse"

version = providers.exec {
	workingDir(rootDir)
	commandLine("git", "show", "-s", "--format=%ct:%h", "--abbrev=12", "HEAD")
}.standardOutput.asText.map { commit ->
	val (timestamp, hash) = commit.trim().split(":", limit = 2)
	val date = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC)
		.format(Instant.ofEpochSecond(timestamp.toLong()))
	"$date-$hash"
}.get()

repositories {
	mavenCentral()
	maven {
		url = uri("https://plugins.gradle.org/m2/")
	}
	maven {
		name = "papermc"
		url = uri("https://repo.papermc.io/repository/maven-public/")
	}
	maven("https://maven.sneakyrp.com/releases")
}

dependencies {
	implementation(kotlin("stdlib"))
	compileOnly("io.papermc.paper:paper-api:26.2.build.117-stable")
	implementation("io.github.agrevster:pocketbase-kotlin:2.7.1")
	compileOnly("io.github.team-sneakymouse:magicspells-core:2026.10.09-17f579714ffe") {
		isTransitive = false
	}
	testImplementation(kotlin("test-junit5"))
	testImplementation("org.junit.jupiter:junit-jupiter:5.14.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.1")
	testImplementation("io.papermc.paper:paper-api:26.2.build.117-stable")
}

tasks.test { useJUnitPlatform() }

tasks.processResources {
	inputs.property("version", project.version.toString())
	filesMatching("paper-plugin.yml") {
		expand("version" to project.version.toString())
	}
}

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

val apiSources = fileTree("src/main/java") {
	include("com/danidipp/sneakypocketbase/*.java")
}

val apiSourcesJar by tasks.registering(Jar::class) {
	archiveClassifier.set("api-sources")
	from(apiSources)
}

val apiJavadoc by tasks.registering(Javadoc::class) {
	source(apiSources)
	classpath = sourceSets.main.get().compileClasspath
	destinationDir = layout.buildDirectory.dir("docs/api").get().asFile
}

val apiJavadocJar by tasks.registering(Jar::class) {
	dependsOn(apiJavadoc)
	archiveClassifier.set("api-javadoc")
	from(apiJavadoc.map { it.destinationDir!! })
}

publishing {
	publications {
		create<MavenPublication>("consumerApi") {
			artifactId = "sneakypocketbase-api"
			artifact(apiJar) { classifier = null }
			artifact(apiSourcesJar) { classifier = "sources" }
			artifact(apiJavadocJar) { classifier = "javadoc" }
			// Publish the API alone, without the plugin's implementation dependencies.
			pom {
				name.set("SneakyPocketbase API")
				description.set("Compile-time API for consumers of the SneakyPocketbase Paper plugin. Requires the matching plugin on the server and Paper API on the compile classpath.")
				url.set("https://github.com/Team-Sneakymouse/SneakyPocketbase")
				scm {
					url.set("https://github.com/Team-Sneakymouse/SneakyPocketbase")
					connection.set("scm:git:https://github.com/Team-Sneakymouse/SneakyPocketbase.git")
				}
			}
		}
	}
	repositories {
		maven {
			name = "sneakyrp"
			url = uri("https://maven.sneakyrp.com/releases")
			credentials(PasswordCredentials::class)
			authentication {
				create<org.gradle.authentication.http.BasicAuthentication>("basic")
			}
		}
	}
}

tasks.withType<PublishToMavenRepository>().configureEach {
	dependsOn(tasks.check)
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
					.file(if (System.getProperty("os.name").startsWith("Windows")) "bin/javap.exe" else "bin/javap").asFile.absolutePath,
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
