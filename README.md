# NowsInstaller

Internet-first installer for Nows, plus per-Minecraft offline installer JARs with embedded Nows artifacts.

The installer itself contains no loader architecture. It reads `install.properties` from `files.nows.space`, installs each declared artifact under the selected Minecraft root's `libraries` directory, writes an inherited Launcher version whose `mainClass` is `space.nows.loader.runtime.NowsLauncher`, then either merges a Nows installation entry into `launcher_profiles.json` or writes an instance reference for third-party launchers.

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

The installer UI is the normal user-facing entrypoint. It lets players choose a supported Minecraft version, auto-detects the main Minecraft folder for Windows, macOS and Linux, and keeps launcher/instance options behind a small advanced options button. Offline installer JARs open the same UI, but lock the Minecraft version to the one bundled inside that offline package.

For launcher-managed instances, choose the launcher target in the UI or use the CLI:

```bash
java -jar NowsInstallerOffline-<version>-mc-<minecraft-version>.jar \
  --launcher modrinth \
  --minecraftDir /path/to/minecraft-root \
  --instanceDir /path/to/modrinth/profile
```

Supported launcher targets are `official`, `modrinth`, `curseforge`, `prism`, `multimc` and `generic`. Official installs still update `launcher_profiles.json`. Prism and MultiMC installs write `mmc-pack.json` plus `patches/space.nows.json` so the instance launches with `space.nows.loader.runtime.NowsLauncher`. Modrinth, CurseForge and generic instance installs put mods in the selected instance game folder and write `nows/launcher-profile.properties`; native app launch support still depends on that launcher accepting custom Minecraft version ids.

Version-specific `space.nows:nows-mc-*` adapter JARs are installed under the selected Minecraft root, but are not added to launcher-owned libraries. The generated profile passes `-Dnows.minecraftAdapterPath=<adapter.jar>` so the adapter is loaded inside Nows' game classloader alongside Minecraft, avoiding duplicate Minecraft classes across classloaders.

The profile icon is embedded from:

```text
src/main/resources/META-INF/nows/branding/nows.png
```

## Build outputs

```bash
./gradlew :repos:NowsInstaller:assemble
```

This builds the normal user-facing installer and offline installers for every supported Minecraft target:

```text
build/libs/NowsInstaller-<version>.jar
build/libs/NowsInstallerOffline-<version>-mc-<minecraft-version>.jar
```

To build only the offline installer set:

```bash
./gradlew :repos:NowsInstaller:allOfflineJars
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

Release manifests should not publish or reference Minecraft client JARs. NowsInstaller prepares the profile client JAR locally from the user's existing vanilla Minecraft install or from Mojang's official download metadata. Legacy Minecraft versions that ship obfuscated client JARs are remapped locally with Mojang's official client mappings during install.

The manifest may also include `mod.<n>` companion mod entries. These are copied to the active game folder's `mods` directory. The 0.5.0 default manifest does not use this for `NowsApiMod`; the title-screen badge is provided by the required `mc/<minecraft-version>` adapter instead.

## Published installer

```text
.publishing/releases/nows/<nows-version>/installers/NowsInstaller-<nows-version>.jar
```

The same installer selects the target Minecraft version at runtime and downloads the matching release manifest from `files.nows.space`.

Offline installers are published beside it, one per supported Minecraft version:

```text
.publishing/releases/nows/<nows-version>/installers/NowsInstallerOffline-<nows-version>-mc-<minecraft-version>.jar
```

This JAR embeds the release manifest, Nows libraries and public runtime libraries. It does not embed Minecraft client files. Run it without `--manifest` or `--artifactDir`; the UI handles the common path and advanced launcher/profile options. For scripted installs, add `--cli` and pass options such as `--minecraftDir`, `--launcher`, `--instanceDir` and `--gameDir`. If the target vanilla client is not already available locally, the installer may still contact Mojang's official services to prepare the local launcher profile.

## Offline local testing

Use the normal installer main class with `--offline` to test files already on disk. In local offline mode the installer never downloads the manifest or artifacts:

```bash
java -cp build/libs/NowsInstaller-<version>.jar space.nows.installer.NowsInstaller \
  --offline \
  --manifest /path/to/install.properties \
  --artifactDir /path/to/offline-libraries \
  --minecraftDir /path/to/test-minecraft
```

The local `install.properties` still uses the normal `artifact.*.path` values. For artifacts whose source is `internet`, place each JAR under `--artifactDir` using that same relative path, for example:

```text
/path/to/offline-libraries/space/nows/nows-core/0.5.0/nows-core-0.5.0.jar
```

Artifacts marked `source=embedded` are still copied from inside the installer JAR. A manifest may also set `artifact.<n>.source=local` and `artifact.<n>.file=relative/or/absolute.jar` for one-off local test jars.
