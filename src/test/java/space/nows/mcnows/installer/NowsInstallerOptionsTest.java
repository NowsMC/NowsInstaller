package space.nows.mcnows.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
        assertTrue(options.usesProfileGameDir());
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
