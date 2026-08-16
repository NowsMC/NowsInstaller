# NowsInstaller

Internet-first Official Launcher installer for Nows, with advanced local offline options for development testing.

The installer itself contains no loader architecture. It reads `install.properties` from `files.nows.space`, installs each declared artifact under `.minecraft/libraries`, writes an inherited Launcher version whose `mainClass` is `space.nows.mcnows.runtime.NowsLauncher`, then merges a Nows installation entry into `launcher_profiles.json`.

The installer is compiled with Java 8 bytecode so it can run on Java 8 and newer.

## Launcher profile safety

NowsInstaller writes only the Nows version directory and Nows launcher profile:

```text
.minecraft/versions/nows-<nows-version>-<minecraft-version>/
.minecraft/nows/profiles/nows-<nows-version>-<minecraft-version>/
.minecraft/launcher_profiles.json
```

The default Minecraft directory follows the official launcher location for the OS:

```text
Windows: %APPDATA%\.minecraft
Linux:   ~/.minecraft
macOS:   ~/Library/Application Support/minecraft
```

The installer logs the resolved `minecraft dir` at startup. If a profile does not appear in the launcher, confirm that path matches the launcher instance you actually use.

Before changing an existing `launcher_profiles.json`, it creates a sibling backup named `launcher_profiles.json.nows-backup-<timestamp>`. Existing Fabric, Forge, Quilt, vanilla, or other custom profiles are preserved; the installer only creates or replaces the profile whose id is `nows-<nows-version>-<minecraft-version>`.

By default, the launcher profile does not set `gameDir`, so the game runs on the launcher's normal Minecraft game folder. Nows mods are discovered from that game folder's `mods` directory first, matching other loaders, and from `.minecraft/nows/profiles/nows-<nows-version>-<minecraft-version>/mods` as an optional Nows-only overlay.

Use `--profileGameDir` from the command line, or the UI's advanced options button, to make the launcher profile use `.minecraft/nows/profiles/nows-<nows-version>-<minecraft-version>/` as its game folder. Use `--gameDir <path>` to choose a custom folder.

The installer UI is the normal user-facing entrypoint. It lets players choose a supported Minecraft version, auto-detects the main Minecraft folder for Windows, macOS and Linux, and keeps profile/offline settings behind a small advanced options button.

The profile icon is embedded from:

```text
src/main/resources/META-INF/nows/branding/nows.png
```

## Build outputs

```bash
./gradlew :repos:NowsInstaller:assemble
```

This builds one user-facing installer JAR:

```text
build/libs/NowsInstaller-<version>.jar
```

Run the installer with:

```bash
java -jar build/libs/NowsInstaller-<version>.jar
```

## Embedded KDL4J

KDL4J is resolved from JitPack first when building the installer and embedded as an untouched JAR resource. GitHub Packages remains available to release environments as a fallback for legacy coordinates. Currently:

```text
com.github.kdl-org:kdl4j:v1.0.1
 -> META-INF/nows/embedded-libs/kdl4j-v1.0.1.jar
 -> .minecraft/libraries/com/github/kdl-org/kdl4j/v1.0.1/kdl4j-v1.0.1.jar
```

End users therefore need no GitHub credentials.

## Internet manifest

Default URL:

```text
https://files.nows.space/releases/nows/<nows-version>/<minecraft-version>/install.properties
```

Use `install.properties.template` as the release template. Release automation should fill SHA-256 values before publishing. For `source=internet` artifacts, the normal installer first tries `artifact.<n>.url`; if that download fails or the URL is missing, it falls back to `artifact.<n>.mavenUrl` or `mavenBaseUrl + artifact.<n>.path`. External downloads must use HTTPS; plain HTTP is accepted only for localhost development.

The manifest may also include `mod.<n>` companion mod entries. These are copied to the active game folder's `mods` directory. The 0.4.0 default manifest does not use this for `NowsApiMod`; the title-screen badge is provided by the required `mc/<minecraft-version>` adapter instead.

## Published installer

```text
.publishing/releases/nows/<nows-version>/installers/NowsInstaller-<nows-version>.jar
```

The same installer selects the target Minecraft version at runtime and downloads the matching release manifest from `files.nows.space`.

## Offline local testing

Use `--offline` to install from files already on disk. In offline mode the installer never downloads the manifest or artifacts:

```bash
java -cp build/libs/NowsInstaller-<version>.jar space.nows.mcnows.installer.NowsInstaller \
  --offline \
  --manifest /path/to/install.properties \
  --artifactDir /path/to/offline-libraries \
  --minecraftDir /path/to/test-minecraft
```

The local `install.properties` still uses the normal `artifact.*.path` values. For artifacts whose source is `internet`, place each JAR under `--artifactDir` using that same relative path, for example:

```text
/path/to/offline-libraries/space/nows/mcnows/nows-core/0.4.0/nows-core-0.4.0.jar
```

Artifacts marked `source=embedded` are still copied from inside the installer JAR. A manifest may also set `artifact.<n>.source=local` and `artifact.<n>.file=relative/or/absolute.jar` for one-off local test jars.
