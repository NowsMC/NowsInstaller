package space.nows.mcnows.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

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
        writeArtifact("space/nows/mcnows/nows-runtime/0.6.2/nows-runtime-0.6.2.jar");
        writeArtifact("space/nows/mcnows/nows-mc-1.20.1/0.6.2/nows-mc-1.20.1-0.6.2.jar");
        Files.write(manifest, (""
                + "format=1\n"
                + "nows.version=0.6.2\n"
                + "minecraft.version=1.20.1\n"
                + "mainClass=space.nows.mcnows.runtime.NowsLauncher\n"
                + "artifact.count=2\n"
                + "artifact.0.coordinate=space.nows.mcnows:nows-runtime:0.6.2\n"
                + "artifact.0.path=space/nows/mcnows/nows-runtime/0.6.2/nows-runtime-0.6.2.jar\n"
                + "artifact.0.source=local\n"
                + "artifact.1.coordinate=space.nows.mcnows:nows-mc-1.20.1:0.6.2\n"
                + "artifact.1.path=space/nows/mcnows/nows-mc-1.20.1/0.6.2/nows-mc-1.20.1-0.6.2.jar\n"
                + "artifact.1.source=local\n"
                + "mod.count=0\n").getBytes(StandardCharsets.UTF_8));

        NowsInstaller.Options options = NowsInstaller.Options.parse(new String[] {
                "--offline",
                "--manifest", manifest.toString(),
                "--nows", "0.6.2",
                "--minecraft", "1.20.1",
                "--launcher", "prism",
                "--minecraftDir", minecraftDir.toString(),
                "--instanceDir", instanceDir.toString()
        });

        assertFalse(options.writesLauncherProfile());
        assertTrue(options.writesMultiMcPack());

        NowsInstaller.install(options, message -> {});

        String pack = new String(Files.readAllBytes(instanceDir.resolve("mmc-pack.json")), StandardCharsets.UTF_8);
        String patch = new String(Files.readAllBytes(instanceDir.resolve("patches").resolve("space.nows.mcnows.json")), StandardCharsets.UTF_8);
        assertTrue(pack.contains("\"uid\": \"space.nows.mcnows\""));
        assertTrue(patch.contains("\"mainClass\": \"space.nows.mcnows.runtime.NowsLauncher\""));
        assertTrue(patch.contains("-Dnows.minecraftVersion=1.20.1"));
        assertTrue(patch.contains("-Dnows.profileId=nows-0.6.2-1.20.1"));
        assertTrue(patch.contains("-Dnows.minecraftAdapterPath="));
        assertTrue(patch.contains("nows-mc-1.20.1-0.6.2.jar"));
        assertTrue(patch.contains("\"name\": \"space.nows.mcnows:nows-runtime:0.6.2\""));
        assertFalse(patch.contains("\"name\": \"space.nows.mcnows:nows-mc-1.20.1:0.6.2\""));
    }

    @Test
    void officialProfileKeepsMinecraftAdapterOffLauncherClasspath() throws Exception {
        Path minecraftDir = temp.resolve("minecraft-root");
        Path manifest = temp.resolve("install.properties");
        writeArtifact("space/nows/mcnows/nows-runtime/0.6.2/nows-runtime-0.6.2.jar");
        writeArtifact("space/nows/mcnows/nows-mc-1.20.1/0.6.2/nows-mc-1.20.1-0.6.2.jar");
        Files.write(manifest, (""
                + "format=1\n"
                + "nows.version=0.6.2\n"
                + "minecraft.version=1.20.1\n"
                + "mainClass=space.nows.mcnows.runtime.NowsLauncher\n"
                + "artifact.count=2\n"
                + "artifact.0.coordinate=space.nows.mcnows:nows-runtime:0.6.2\n"
                + "artifact.0.path=space/nows/mcnows/nows-runtime/0.6.2/nows-runtime-0.6.2.jar\n"
                + "artifact.0.source=local\n"
                + "artifact.1.coordinate=space.nows.mcnows:nows-mc-1.20.1:0.6.2\n"
                + "artifact.1.path=space/nows/mcnows/nows-mc-1.20.1/0.6.2/nows-mc-1.20.1-0.6.2.jar\n"
                + "artifact.1.source=local\n"
                + "mod.count=0\n").getBytes(StandardCharsets.UTF_8));

        NowsInstaller.Options options = NowsInstaller.Options.parse(new String[] {
                "--offline",
                "--manifest", manifest.toString(),
                "--nows", "0.6.2",
                "--minecraft", "1.20.1",
                "--minecraftDir", minecraftDir.toString()
        });

        NowsInstaller.install(options, message -> {});

        String profileJson = new String(Files.readAllBytes(minecraftDir
                .resolve("versions")
                .resolve("nows-0.6.2-1.20.1")
                .resolve("nows-0.6.2-1.20.1.json")), StandardCharsets.UTF_8);
        assertTrue(profileJson.contains("\"name\":\"space.nows.mcnows:nows-runtime:0.6.2\""));
        assertFalse(profileJson.contains("\"name\":\"space.nows.mcnows:nows-mc-1.20.1:0.6.2\""));
        assertTrue(profileJson.contains("-Dnows.minecraftAdapterPath="));
        assertTrue(profileJson.contains("nows-mc-1.20.1-0.6.2.jar"));
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
}
