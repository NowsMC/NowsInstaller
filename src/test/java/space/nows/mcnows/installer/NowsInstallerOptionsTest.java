package space.nows.mcnows.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NowsInstallerOptionsTest {
    @TempDir
    Path temp;

    @Test
    void officialLauncherWritesLauncherProfile() {
        NowsInstaller.Options options = NowsInstaller.Options.parse(new String[] {
                "--minecraftDir", temp.resolve("minecraft").toString()
        });

        assertEquals("official", options.launcher);
        assertTrue(options.writesLauncherProfile());
        assertFalse(options.usesProfileGameDir());
    }

    @Test
    void instanceLauncherRequiresInstanceDirectory() {
        assertThrows(IllegalArgumentException.class, () ->
                NowsInstaller.Options.parse(new String[] {"--launcher", "modrinth"}));
    }

    @Test
    void modrinthLauncherUsesInstanceGameDirectory() {
        Path minecraftDir = temp.resolve("minecraft-root");
        Path instanceDir = temp.resolve("modrinth-profile");

        NowsInstaller.Options options = NowsInstaller.Options.parse(new String[] {
                "--launcher", "modrinth",
                "--minecraftDir", minecraftDir.toString(),
                "--instanceDir", instanceDir.toString()
        });

        assertEquals("modrinth", options.launcher);
        assertEquals(instanceDir, options.instanceDir);
        assertFalse(options.writesLauncherProfile());
        assertFalse(options.writesMultiMcPack());
        assertTrue(options.usesProfileGameDir());
    }

    @Test
    void prismLauncherWritesRunnablePackPatch() throws Exception {
        Path minecraftDir = temp.resolve("minecraft-root");
        Path instanceDir = temp.resolve("PrismInstance");
        Path manifest = temp.resolve("install.properties");
        writeManifest(manifest, "1.20.1");

        NowsInstaller.Options options = NowsInstaller.Options.parse(new String[] {
                "--offline",
                "--manifest", manifest.toString(),
                "--nows", "0.7.0",
                "--minecraft", "1.20.1",
                "--launcher", "prism",
                "--minecraftDir", minecraftDir.toString(),
                "--instanceDir", instanceDir.toString()
        });

        assertFalse(options.writesLauncherProfile());
        assertTrue(options.writesMultiMcPack());

        writeNamedMinecraftJar(minecraftDir, "1.20.1");
        NowsInstaller.install(options, message -> {});

        String pack = new String(Files.readAllBytes(instanceDir.resolve("mmc-pack.json")), StandardCharsets.UTF_8);
        String patch = new String(Files.readAllBytes(instanceDir.resolve("patches").resolve("space.nows.mcnows.json")), StandardCharsets.UTF_8);
        assertTrue(pack.contains("\"uid\": \"space.nows.mcnows\""));
        assertTrue(patch.contains("\"mainClass\": \"space.nows.mcnows.runtime.NowsLauncher\""));
        assertTrue(patch.contains("-Dnows.minecraftVersion=1.20.1"));
        assertTrue(patch.contains("-Dnows.profileId=nows-0.7.0-1.20.1"));
        assertTrue(patch.contains("-Dnows.minecraftAdapterPath="));
        assertTrue(patch.contains("nows-mc-1.20.1-0.7.0.jar"));
        assertTrue(patch.contains("\"name\": \"space.nows.mcnows:nows-runtime:0.7.0\""));
        assertFalse(patch.contains("\"name\": \"space.nows.mcnows:nows-mc-1.20.1:0.7.0\""));
    }

    @Test
    void launcherMetadataForEverySupportedVersionUsesAdapterPath() throws Exception {
        for (String minecraftVersion : NowsInstaller.supportedMinecraftVersions()) {
            Path manifest = temp.resolve("install-" + minecraftVersion + ".properties");
            writeManifest(manifest, minecraftVersion);

            Path minecraftDir = temp.resolve("official-" + minecraftVersion);
            writeNamedMinecraftJar(minecraftDir, minecraftVersion);
            NowsInstaller.install(NowsInstaller.Options.parse(new String[] {
                    "--offline",
                    "--manifest", manifest.toString(),
                    "--nows", "0.7.0",
                    "--minecraft", minecraftVersion,
                    "--minecraftDir", minecraftDir.toString()
            }), message -> {});
            String profileJson = new String(Files.readAllBytes(minecraftDir
                    .resolve("versions")
                    .resolve("nows-0.7.0-" + minecraftVersion)
                    .resolve("nows-0.7.0-" + minecraftVersion + ".json")), StandardCharsets.UTF_8);
            assertLauncherMetadataUsesAdapterPath(profileJson, minecraftVersion);

            Path prismMinecraftDir = temp.resolve("prism-root-" + minecraftVersion);
            Path instanceDir = temp.resolve("prism-instance-" + minecraftVersion);
            writeNamedMinecraftJar(prismMinecraftDir, minecraftVersion);
            NowsInstaller.install(NowsInstaller.Options.parse(new String[] {
                    "--offline",
                    "--manifest", manifest.toString(),
                    "--nows", "0.7.0",
                    "--minecraft", minecraftVersion,
                    "--launcher", "prism",
                    "--minecraftDir", prismMinecraftDir.toString(),
                    "--instanceDir", instanceDir.toString()
            }), message -> {});
            String prismPatch = new String(Files.readAllBytes(instanceDir
                    .resolve("patches")
                    .resolve("space.nows.mcnows.json")), StandardCharsets.UTF_8);
            assertLauncherMetadataUsesAdapterPath(prismPatch, minecraftVersion);
        }
    }

    @Test
    void officialLauncherProfilesRequestJavaRuntimeForEverySupportedVersion() throws Exception {
        for (String minecraftVersion : NowsInstaller.supportedMinecraftVersions()) {
            Path manifest = temp.resolve("java-runtime-" + minecraftVersion + ".properties");
            writeManifest(manifest, minecraftVersion);

            Path minecraftDir = temp.resolve("java-runtime-official-" + minecraftVersion);
            writeNamedMinecraftJar(minecraftDir, minecraftVersion);
            NowsInstaller.install(NowsInstaller.Options.parse(new String[] {
                    "--offline",
                    "--manifest", manifest.toString(),
                    "--nows", "0.7.0",
                    "--minecraft", minecraftVersion,
                    "--minecraftDir", minecraftDir.toString()
            }), message -> {});

            String profileJson = new String(Files.readAllBytes(minecraftDir
                    .resolve("versions")
                    .resolve("nows-0.7.0-" + minecraftVersion)
                    .resolve("nows-0.7.0-" + minecraftVersion + ".json")), StandardCharsets.UTF_8);
            NowsInstaller.MinecraftJavaRuntime runtime = NowsInstaller.minecraftJavaRuntime(minecraftVersion);
            assertTrue(profileJson.contains("\"javaVersion\": {\"component\":\"" + runtime.component
                            + "\",\"majorVersion\":" + runtime.majorVersion + "}"),
                    minecraftVersion + " official launcher profile must request the Nows-compatible Java runtime");
        }
    }

    @Test
    void minecraftJavaRuntimeMatchesKnownLauncherFamilies() {
        assertEquals(17, NowsInstaller.minecraftJavaRuntime("1.16.5").majorVersion);
        assertEquals("java-runtime-gamma", NowsInstaller.minecraftJavaRuntime("1.16.5").component);
        assertEquals(17, NowsInstaller.minecraftJavaRuntime("1.20.1").majorVersion);
        assertEquals(21, NowsInstaller.minecraftJavaRuntime("1.20.6").majorVersion);
        assertEquals(21, NowsInstaller.minecraftJavaRuntime("1.21.11").majorVersion);
        assertEquals(25, NowsInstaller.minecraftJavaRuntime("26.2").majorVersion);
        assertEquals("java-runtime-epsilon", NowsInstaller.minecraftJavaRuntime("26.2").component);
    }

    @Test
    void offlineOfficialInstallFailsWithoutLocalMinecraftClientJar() throws Exception {
        Path manifest = temp.resolve("missing-client.properties");
        writeManifest(manifest, "1.18.2");

        Exception thrown = assertThrows(Exception.class, () ->
                NowsInstaller.install(NowsInstaller.Options.parse(new String[] {
                        "--offline",
                        "--manifest", manifest.toString(),
                        "--nows", "0.7.0",
                        "--minecraft", "1.18.2",
                        "--minecraftDir", temp.resolve("missing-client-minecraft").toString()
                }), message -> {}));

        assertTrue(thrown.getMessage().contains("local client jar is missing"));
    }

    @Test
    void embeddedOfflinePayloadKeepsInstallerOffline() throws Exception {
        NowsInstaller.Options options = NowsInstaller.Options.parse(new String[] {
                "--minecraftDir", temp.resolve("minecraft").toString()
        }).withEmbeddedOfflinePayload();

        assertTrue(options.offline);
        assertTrue(options.embeddedArtifacts);
        assertEquals("0.7.0", options.nowsVersion);
        assertEquals("1.20.1", options.minecraftVersion);
    }

    @Test
    void prismAndMultiMcDefaultToDotMinecraftInsideInstance() {
        Path instanceDir = temp.resolve("PrismInstance");

        assertEquals(instanceDir.resolve(".minecraft").toAbsolutePath().normalize(),
                NowsInstaller.instanceGameDirectory("prism", instanceDir));
        assertEquals(instanceDir.resolve(".minecraft").toAbsolutePath().normalize(),
                NowsInstaller.instanceGameDirectory("multimc", instanceDir));
    }

    @Test
    void instanceGameDirectoryPrefersExistingGameFolders() throws Exception {
        Path curseforge = temp.resolve("curseforge-instance");
        Files.createDirectories(curseforge.resolve("mods"));
        assertEquals(curseforge.toAbsolutePath().normalize(),
                NowsInstaller.instanceGameDirectory("curseforge", curseforge));

        Path generic = temp.resolve("generic-instance");
        Files.createDirectories(generic.resolve("minecraft").resolve("mods"));
        assertEquals(generic.resolve("minecraft").toAbsolutePath().normalize(),
                NowsInstaller.instanceGameDirectory("generic", generic));
    }

    private void writeArtifact(String relativePath) throws Exception {
        Path artifact = temp.resolve(relativePath);
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, ("test artifact " + relativePath).getBytes(StandardCharsets.UTF_8));
    }

    private void writeManifest(Path manifest, String minecraftVersion) throws Exception {
        writeArtifact("space/nows/mcnows/nows-runtime/0.7.0/nows-runtime-0.7.0.jar");
        writeArtifact("space/nows/mcnows/nows-mc-" + minecraftVersion + "/0.7.0/nows-mc-"
                + minecraftVersion + "-0.7.0.jar");
        Files.write(manifest, (""
                + "format=1\n"
                + "nows.version=0.7.0\n"
                + "minecraft.version=" + minecraftVersion + "\n"
                + "mainClass=space.nows.mcnows.runtime.NowsLauncher\n"
                + "artifact.count=2\n"
                + "artifact.0.coordinate=space.nows.mcnows:nows-runtime:0.7.0\n"
                + "artifact.0.path=space/nows/mcnows/nows-runtime/0.7.0/nows-runtime-0.7.0.jar\n"
                + "artifact.0.source=local\n"
                + "artifact.1.coordinate=space.nows.mcnows:nows-mc-" + minecraftVersion + ":0.7.0\n"
                + "artifact.1.path=space/nows/mcnows/nows-mc-" + minecraftVersion
                + "/0.7.0/nows-mc-" + minecraftVersion + "-0.7.0.jar\n"
                + "artifact.1.source=local\n"
                + "mod.count=0\n").getBytes(StandardCharsets.UTF_8));
    }

    private void writeNamedMinecraftJar(Path minecraftDir, String minecraftVersion) throws Exception {
        Path jar = minecraftDir.resolve("versions").resolve(minecraftVersion).resolve(minecraftVersion + ".jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("net/minecraft/client/Minecraft.class"));
            output.closeEntry();
            output.putNextEntry(new JarEntry("net/minecraft/client/main/Main.class"));
            output.closeEntry();
        }
    }

    private static void assertLauncherMetadataUsesAdapterPath(String metadata, String minecraftVersion) {
        assertTrue(metadata.contains("space.nows.mcnows:nows-runtime:0.7.0"));
        assertFalse(metadata.contains("\"name\":\"space.nows.mcnows:nows-mc-" + minecraftVersion + ":0.7.0\""));
        assertFalse(metadata.contains("\"name\": \"space.nows.mcnows:nows-mc-" + minecraftVersion + ":0.7.0\""));
        assertTrue(metadata.contains("-Dnows.minecraftAdapterPath="));
        assertTrue(metadata.contains("nows-mc-" + minecraftVersion + "-0.7.0.jar"));
    }
}
