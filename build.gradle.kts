import java.util.Properties

plugins { application }

val kdl4jVersion = providers.gradleProperty("kdl4j_version")
val gebCoreVersion = providers.gradleProperty("geb_core_version")
val reactorVersion = providers.gradleProperty("reactor_version")
val disruptorVersion = providers.gradleProperty("disruptor_version")
val mixinVersion = providers.gradleProperty("mixin_version")
val moshiVersion = providers.gradleProperty("moshi_version")
val okioVersion = providers.gradleProperty("okio_version")
val kotlinVersion = providers.gradleProperty("kotlin_version")
val jetbrainsAnnotationsVersion = providers.gradleProperty("jetbrains_annotations_version")
val minecraftVersion = providers.gradleProperty("minecraft_version")
val nowsReleaseBaseUrl = providers.gradleProperty("nows_release_base_url")

// GitHub Packages are resolved while building NowsInstaller, then embedded in the
// installer itself. End users therefore do not need GitHub credentials.
val embeddedGitHubPackages by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val offlineMavenArtifacts by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    embeddedGitHubPackages("dev.kdl:kdl4j:${kdl4jVersion.get()}")

    offlineMavenArtifacts("foo.zaaarf.geb:core:${gebCoreVersion.get()}")
    offlineMavenArtifacts("io.projectreactor:reactor-core:${reactorVersion.get()}")
    offlineMavenArtifacts("org.reactivestreams:reactive-streams:1.0.4")
    offlineMavenArtifacts("com.lmax:disruptor:${disruptorVersion.get()}")
    offlineMavenArtifacts("com.squareup.moshi:moshi:${moshiVersion.get()}")
    offlineMavenArtifacts("com.squareup.okio:okio-jvm:${okioVersion.get()}")
    offlineMavenArtifacts("org.jetbrains.kotlin:kotlin-stdlib:${kotlinVersion.get()}")
    offlineMavenArtifacts("org.jetbrains.kotlin:kotlin-stdlib-jdk7:${kotlinVersion.get()}")
    offlineMavenArtifacts("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${kotlinVersion.get()}")
    offlineMavenArtifacts("org.jetbrains:annotations:${jetbrainsAnnotationsVersion.get()}")
    offlineMavenArtifacts("org.spongepowered:mixin:${mixinVersion.get()}")
    offlineMavenArtifacts("org.ow2.asm:asm:9.8")
    offlineMavenArtifacts("org.ow2.asm:asm-tree:9.8")
    offlineMavenArtifacts("org.ow2.asm:asm-commons:9.8")
    offlineMavenArtifacts("org.ow2.asm:asm-analysis:9.8")
    offlineMavenArtifacts("org.ow2.asm:asm-util:9.8")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

val embeddedDir = layout.buildDirectory.dir("generated/embedded-libs")
val prepareEmbeddedLibraries by tasks.registering(Copy::class) {
    from(embeddedGitHubPackages)
    into(embeddedDir)
}

val installerDefaultsDir = layout.buildDirectory.dir("generated/installer-defaults")
val prepareInstallerDefaults by tasks.registering {
    inputs.property("nowsVersion", project.version.toString())
    inputs.property("minecraftVersion", minecraftVersion)
    outputs.dir(installerDefaultsDir)

    doLast {
        val output = installerDefaultsDir.get().file("defaults.properties").asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            nows.version=${project.version}
            minecraft.version=${minecraftVersion.get()}
            """.trimIndent() + "\n"
        )
    }
}

val offlinePayloadDir = layout.buildDirectory.dir("generated/offline-installer")
fun offlineModuleArtifactsFor(minecraft: String): Map<String, String> = mapOf(
    ":core" to "space/nows/mcnows/nows-core/${project.version}/nows-core-${project.version}.jar",
    ":minecraft" to "space/nows/mcnows/nows-minecraft/${project.version}/nows-minecraft-${project.version}.jar",
    ":mc:$minecraft" to "space/nows/mcnows/nows-mc-$minecraft/${project.version}/nows-mc-$minecraft-${project.version}.jar",
    ":integrations:kdl" to "space/nows/mcnows/nows-integration-kdl/${project.version}/nows-integration-kdl-${project.version}.jar",
    ":integrations:geb" to "space/nows/mcnows/nows-integration-geb/${project.version}/nows-integration-geb-${project.version}.jar",
    ":integrations:logging" to "space/nows/mcnows/nows-integration-logging/${project.version}/nows-integration-logging-${project.version}.jar",
    ":integrations:network" to "space/nows/mcnows/nows-integration-network/${project.version}/nows-integration-network-${project.version}.jar",
    ":integrations:mixin" to "space/nows/mcnows/nows-integration-mixin/${project.version}/nows-integration-mixin-${project.version}.jar",
    ":runtime" to "space/nows/mcnows/nows-runtime/${project.version}/nows-runtime-${project.version}.jar"
)
val offlineMavenArtifactPaths = mapOf(
    "core-${gebCoreVersion.get()}.jar" to "foo/zaaarf/geb/core/${gebCoreVersion.get()}/core-${gebCoreVersion.get()}.jar",
    "reactor-core-${reactorVersion.get()}.jar" to "io/projectreactor/reactor-core/${reactorVersion.get()}/reactor-core-${reactorVersion.get()}.jar",
    "reactive-streams-1.0.4.jar" to "org/reactivestreams/reactive-streams/1.0.4/reactive-streams-1.0.4.jar",
    "disruptor-${disruptorVersion.get()}.jar" to "com/lmax/disruptor/${disruptorVersion.get()}/disruptor-${disruptorVersion.get()}.jar",
    "moshi-${moshiVersion.get()}.jar" to "com/squareup/moshi/moshi/${moshiVersion.get()}/moshi-${moshiVersion.get()}.jar",
    "okio-jvm-${okioVersion.get()}.jar" to "com/squareup/okio/okio-jvm/${okioVersion.get()}/okio-jvm-${okioVersion.get()}.jar",
    "kotlin-stdlib-${kotlinVersion.get()}.jar" to "org/jetbrains/kotlin/kotlin-stdlib/${kotlinVersion.get()}/kotlin-stdlib-${kotlinVersion.get()}.jar",
    "kotlin-stdlib-jdk7-${kotlinVersion.get()}.jar" to "org/jetbrains/kotlin/kotlin-stdlib-jdk7/${kotlinVersion.get()}/kotlin-stdlib-jdk7-${kotlinVersion.get()}.jar",
    "kotlin-stdlib-jdk8-${kotlinVersion.get()}.jar" to "org/jetbrains/kotlin/kotlin-stdlib-jdk8/${kotlinVersion.get()}/kotlin-stdlib-jdk8-${kotlinVersion.get()}.jar",
    "annotations-${jetbrainsAnnotationsVersion.get()}.jar" to "org/jetbrains/annotations/${jetbrainsAnnotationsVersion.get()}/annotations-${jetbrainsAnnotationsVersion.get()}.jar",
    "mixin-${mixinVersion.get()}.jar" to "org/spongepowered/mixin/${mixinVersion.get()}/mixin-${mixinVersion.get()}.jar",
    "asm-9.8.jar" to "org/ow2/asm/asm/9.8/asm-9.8.jar",
    "asm-tree-9.8.jar" to "org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar",
    "asm-commons-9.8.jar" to "org/ow2/asm/asm-commons/9.8/asm-commons-9.8.jar",
    "asm-analysis-9.8.jar" to "org/ow2/asm/asm-analysis/9.8/asm-analysis-9.8.jar",
    "asm-util-9.8.jar" to "org/ow2/asm/asm-util/9.8/asm-util-9.8.jar"
)

val offlineManifestFile = layout.buildDirectory.file("generated/offline-installer-manifest/install.properties")
val prepareOfflineManifest by tasks.registering {
    inputs.file("install.properties.template")
    inputs.property("nowsVersion", project.version.toString())
    inputs.property("minecraftVersion", minecraftVersion)
    inputs.property("nowsReleaseBaseUrl", nowsReleaseBaseUrl)
    outputs.file(offlineManifestFile)

    doLast {
        val nows = project.version.toString()
        val minecraft = minecraftVersion.get()
        val releaseBaseUrl = "${nowsReleaseBaseUrl.get()}/$nows/$minecraft"
        val manifest = Properties()
        file("install.properties.template").inputStream().use { input -> manifest.load(input) }

        manifest["nows.version"] = nows
        manifest["minecraft.version"] = minecraft
        manifest["releaseBaseUrl"] = releaseBaseUrl
        offlineModuleArtifactsFor(minecraft).forEach { (projectPath, relativePath) ->
            val index = offlineModuleArtifactIndex(projectPath, minecraft) ?: return@forEach
            val artifactId = offlineModuleArtifactId(projectPath, minecraft)
            manifest["artifact.$index.coordinate"] = "space.nows.mcnows:$artifactId:$nows"
            manifest["artifact.$index.path"] = relativePath
        }
        manifest["artifact.18.coordinate"] = "space.nows.mcnows:nows-mc-$minecraft:$nows"
        manifest["artifact.18.path"] = "space/nows/mcnows/nows-mc-$minecraft/$nows/nows-mc-$minecraft-$nows.jar"
        val count = manifest.getProperty("artifact.count").toInt()
        for (index in 0 until count) {
            val prefix = "artifact.$index."
            if (manifest.getProperty(prefix + "source", "internet") == "embedded") {
                continue
            }
            manifest[prefix + "url"] = "$releaseBaseUrl/libraries/${manifest.getProperty(prefix + "path")}"
        }

        val output = offlineManifestFile.get().asFile
        output.parentFile.mkdirs()
        output.bufferedWriter().use { writer ->
            writer.appendLine("# Generated by :repos:NowsInstaller:prepareOfflineManifest")
            manifest.stringPropertyNames().sortedWith(compareBy<String> {
                it.substringBefore('.')
            }.thenBy { key ->
                key.split('.').getOrNull(1)?.toIntOrNull() ?: -1
            }.thenBy { it }).forEach { key: String ->
                writer.append(key).append("=").append(manifest.getProperty(key)).appendLine()
            }
        }
    }
}

val offlineMinecraftVersion = minecraftVersion.get()
val offlineModuleArtifacts = offlineModuleArtifactsFor(offlineMinecraftVersion)
fun offlineModuleArtifactIndex(projectPath: String, minecraft: String): Int? = when (projectPath) {
    ":core" -> 0
    ":minecraft" -> 1
    ":integrations:kdl" -> 2
    ":integrations:geb" -> 3
    ":integrations:logging" -> 4
    ":integrations:network" -> 19
    ":integrations:mixin" -> 5
    ":runtime" -> 6
    ":mc:$minecraft" -> 18
    else -> null
}

fun offlineModuleArtifactId(projectPath: String, minecraft: String): String = when (projectPath) {
    ":core" -> "nows-core"
    ":minecraft" -> "nows-minecraft"
    ":integrations:kdl" -> "nows-integration-kdl"
    ":integrations:geb" -> "nows-integration-geb"
    ":integrations:logging" -> "nows-integration-logging"
    ":integrations:network" -> "nows-integration-network"
    ":integrations:mixin" -> "nows-integration-mixin"
    ":runtime" -> "nows-runtime"
    ":mc:$minecraft" -> "nows-mc-$minecraft"
    else -> throw GradleException("No artifact id registered for $projectPath")
}

val prepareOfflinePayload by tasks.registering(Copy::class) {
    dependsOn(prepareOfflineManifest)
    dependsOn(offlineModuleArtifacts.keys.map { project(it).tasks.named("jar") })
    doFirst {
        delete(offlinePayloadDir)
    }
    from(offlineManifestFile) {
        into("META-INF/nows/offline")
        rename { "install.properties" }
    }
    offlineModuleArtifacts.forEach { (projectPath, targetPath) ->
        val targetDir = targetPath.substringBeforeLast("/")
        val targetFile = targetPath.substringAfterLast("/")
        val jarFile = project(projectPath).tasks.named<Jar>("jar").flatMap { it.archiveFile }
        from(jarFile) {
            into("META-INF/nows/offline-libraries/$targetDir")
            rename { targetFile }
        }
    }
    from(offlineMavenArtifacts) {
        eachFile {
            val targetPath = offlineMavenArtifactPaths[name]
                ?: throw GradleException("No offline installer path registered for $name")
            path = "META-INF/nows/offline-libraries/$targetPath"
        }
        includeEmptyDirs = false
    }
    into(offlinePayloadDir)
}

tasks.processResources {
    dependsOn(prepareEmbeddedLibraries, prepareInstallerDefaults)
    from(embeddedDir) { into("META-INF/nows/embedded-libs") }
    from(installerDefaultsDir) { into("META-INF/nows/installer") }
}

application { mainClass.set("space.nows.mcnows.installer.NowsInstaller") }

tasks.jar {
    dependsOn(prepareEmbeddedLibraries)
    archiveBaseName.set("NowsInstaller-cli")
    manifest { attributes("Main-Class" to application.mainClass.get()) }
}

val guiJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds the Swing GUI Nows installer JAR."
    dependsOn(tasks.classes)
    archiveBaseName.set("NowsInstaller-ui")
    from(sourceSets.main.get().output)
    manifest { attributes("Main-Class" to "space.nows.mcnows.installer.NowsInstallerGui") }
}

val offlineJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds the fully offline Nows installer JAR with all manifest artifacts embedded."
    dependsOn(tasks.classes, prepareOfflinePayload)
    archiveBaseName.set("NowsInstaller-offline")
    from(sourceSets.main.get().output)
    from(offlinePayloadDir)
    manifest { attributes("Main-Class" to "space.nows.mcnows.installer.NowsInstallerOffline") }
}

tasks.assemble {
    dependsOn(guiJar, offlineJar)
}
