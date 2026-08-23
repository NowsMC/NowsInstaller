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
        Files.write(manifest, (""
                + "format=1\n"
                + "nows.version=0.6.2\n"
                + "minecraft.version=1.20.1\n"
                + "mainClass=space.nows.mcnows.runtime.NowsLauncher\n"
                + "artifact.count=0\n"
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
}
