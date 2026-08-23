import java.security.MessageDigest
import java.util.Properties

plugins { java }

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
val kdl4jCoordinate = "com.github.kdl-org:kdl4j:v${kdl4jVersion.get()}"
val installerMainClass = "space.nows.mcnows.installer.NowsInstallerGui"

fun compareVersionStrings(left: String, right: String): Int {
    val leftParts = left.split(".").map { it.toIntOrNull() ?: 0 }
    val rightParts = right.split(".").map { it.toIntOrNull() ?: 0 }
    val length = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until length) {
        val diff = (leftParts.getOrNull(index) ?: 0) - (rightParts.getOrNull(index) ?: 0)
        if (diff != 0) return diff
    }
    return 0
}

val supportedMinecraftVersions = rootProject.layout.projectDirectory.dir("mc").asFile
    .listFiles { file -> file.isDirectory && file.resolve("build.gradle.kts").isFile }
    .orEmpty()
    .map { it.name }
    .sortedWith(::compareVersionStrings)
    .asReversed()

// KDL4J is resolved while building NowsInstaller, then embedded in the installer
// itself. JitPack is preferred; end users therefore do not need GitHub credentials.
val embeddedKdlLibraries by configurations.creating {
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
    implementation(project(":repos:NowsRemapper"))
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    embeddedKdlLibraries(kdl4jCoordinate)

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
val prepareEmbeddedLibraries by tasks.registering(Sync::class) {
    from(embeddedKdlLibraries)
    into(embeddedDir)
}

val installerDefaultsDir = layout.buildDirectory.dir("generated/installer-defaults")
val prepareInstallerDefaults by tasks.registering {
    inputs.property("nowsVersion", project.version.toString())
    inputs.property("minecraftVersion", minecraftVersion)
    inputs.property("minecraftVersions", supportedMinecraftVersions.joinToString(","))
    outputs.dir(installerDefaultsDir)

    doLast {
        val output = installerDefaultsDir.get().file("defaults.properties").asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            nows.version=${project.version}
            minecraft.version=${minecraftVersion.get()}
            minecraft.versions=${supportedMinecraftVersions.joinToString(",")}
            """.trimIndent() + "\n"
        )
    }
}

val offlineMinecraftVersion = minecraftVersion.get()

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

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

fun minecraftTaskSuffix(minecraft: String): String =
    "Mc" + minecraft.replace('.', '_')

fun prepareOfflineInstallerManifestTask(minecraft: String) =
    tasks.register("prepareOffline${minecraftTaskSuffix(minecraft)}Manifest") {
        val offlineManifestFile = layout.buildDirectory.file("generated/offline-installer-manifest/$minecraft/install.properties")
        inputs.file("install.properties.template")
        inputs.property("nowsVersion", project.version.toString())
        inputs.property("minecraftVersion", minecraft)
        inputs.property("nowsReleaseBaseUrl", nowsReleaseBaseUrl)
        outputs.file(offlineManifestFile)

        doLast {
            val nows = project.version.toString()
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
                writer.appendLine("# Generated by :repos:NowsInstaller:prepareOffline${minecraftTaskSuffix(minecraft)}Manifest")
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

val offlineManifestTasks = supportedMinecraftVersions.associateWith { minecraft ->
    prepareOfflineInstallerManifestTask(minecraft)
}

fun prepareOfflineInstallerPayloadTask(minecraft: String) =
    tasks.register<Copy>("prepareOffline${minecraftTaskSuffix(minecraft)}Payload") {
        val offlineManifestFile = layout.buildDirectory.file("generated/offline-installer-manifest/$minecraft/install.properties")
        val offlinePayloadDir = layout.buildDirectory.dir("generated/offline-installer/$minecraft")
        val offlineModuleArtifacts = offlineModuleArtifactsFor(minecraft)
        dependsOn(offlineManifestTasks.getValue(minecraft))
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

val offlinePayloadTasks = supportedMinecraftVersions.associateWith { minecraft ->
    prepareOfflineInstallerPayloadTask(minecraft)
}

tasks.processResources {
    dependsOn(prepareEmbeddedLibraries, prepareInstallerDefaults)
    from(embeddedDir) { into("META-INF/nows/embedded-libs") }
    from(installerDefaultsDir) { into("META-INF/nows/installer") }
}

tasks.jar {
    dependsOn(prepareEmbeddedLibraries, project(":repos:NowsRemapper").tasks.named("jar"))
    archiveBaseName.set("NowsInstaller")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.map { classpath ->
        classpath.filter { it.name.startsWith("nows-remapper-") || it.name.startsWith("asm-") || it.name.startsWith("asm-commons-") }
            .map { zipTree(it) }
    })
    manifest { attributes("Main-Class" to installerMainClass) }
}

tasks.test {
    useJUnitPlatform()
}

fun offlineJarTaskName(minecraft: String): String =
    if (minecraft == offlineMinecraftVersion) {
        "offlineJar"
    } else {
        "offline${minecraftTaskSuffix(minecraft)}Jar"
    }

val offlineJarTasks = supportedMinecraftVersions.associateWith { minecraft ->
    tasks.register<Jar>(offlineJarTaskName(minecraft)) {
        val offlinePayloadDir = layout.buildDirectory.dir("generated/offline-installer/$minecraft")
        dependsOn(tasks.classes, offlinePayloadTasks.getValue(minecraft), project(":repos:NowsRemapper").tasks.named("jar"))
        group = "build"
        description = "Builds an offline Nows installer for Minecraft $minecraft with the manifest and libraries embedded."
        archiveBaseName.set("NowsInstallerOffline")
        archiveClassifier.set("mc-$minecraft")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(sourceSets.main.get().output)
        from(configurations.runtimeClasspath.map { classpath ->
            classpath.filter { it.name.startsWith("nows-remapper-") || it.name.startsWith("asm-") || it.name.startsWith("asm-commons-") }
                .map { zipTree(it) }
        })
        from(offlinePayloadDir)
        manifest { attributes("Main-Class" to "space.nows.mcnows.installer.NowsInstallerOffline") }
    }
}

val allOfflineJars by tasks.registering {
    group = "build"
    description = "Builds offline Nows installers for every supported Minecraft version."
    dependsOn(offlineJarTasks.values)
}

tasks.assemble {
    dependsOn(allOfflineJars)
}

tasks.named("sourcesJar") {
    enabled = false
}
