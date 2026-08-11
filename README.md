# NowsInstaller

Internet-first Official Launcher installer for Nows, with a local offline mode for development testing.

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

Use `--profileGameDir` in the CLI installer, or enable `Use profile-local game folder` in the UI installer, to make the launcher profile use `.minecraft/nows/profiles/nows-<nows-version>-<minecraft-version>/` as its game folder.

The profile icon is embedded from:

```text
src/main/resources/META-INF/nows/branding/nows.png
```

## Build outputs

```bash
./gradlew :repos:NowsInstaller:assemble
```

This builds separate installer JARs:

```text
build/libs/NowsInstaller-cli-<version>.jar
build/libs/NowsInstaller-ui-<version>.jar
build/libs/NowsInstaller-offline-<version>.jar
```

Run the CLI installer with:

```bash
java -jar build/libs/NowsInstaller-cli-<version>.jar
```

Run the Swing UI installer with:

```bash
java -jar build/libs/NowsInstaller-ui-<version>.jar
```

Run the fully offline installer with:

```bash
java -jar build/libs/NowsInstaller-offline-<version>.jar
```

## GitHub Packages

GitHub Package dependencies are resolved when building the installer and embedded as untouched JAR resources. Currently:

```text
dev.kdl:kdl4j:1.0.1
 -> META-INF/nows/embedded-libs/kdl4j-1.0.1.jar
 -> .minecraft/libraries/dev/kdl/kdl4j/1.0.1/kdl4j-1.0.1.jar
```

End users therefore need no GitHub credentials. The release build does.

## Internet manifest

Default URL:

```text
https://files.nows.space/releases/nows/<nows-version>/<minecraft-version>/install.properties
```

Use `install.properties.template` as the release template. Release automation should fill SHA-256 values before publishing. For `source=internet` artifacts, the normal installer first tries `artifact.<n>.url`; if that download fails or the URL is missing, it falls back to `artifact.<n>.mavenUrl` or `mavenBaseUrl + artifact.<n>.path`.

The manifest may also include `mod.<n>` companion mod entries. These are copied to the main `.minecraft/mods` directory. The 0.4.0 default manifest does not use this for `NowsApiMod`; the title-screen badge is provided by the required `mc/<minecraft-version>` adapter instead.

## Fully offline installer

`NowsInstaller-offline-<version>.jar` embeds the release manifest and every non-Minecraft artifact listed by the installer manifest. Its Nows module payloads are taken from this workspace's Gradle project JAR tasks (`:core:jar`, `:runtime:jar`, integrations, and Minecraft support), so a local build installs the local project output. At install time it copies those embedded payloads into `.minecraft/libraries` and writes the same launcher profile as the normal installer without contacting `files.nows.space` or Maven.

The CLI/UI installers are generic entrypoints. The fully offline installer is Minecraft-version-specific because it embeds one processed manifest and one `nows-mc-<minecraft-version>` adapter. `./gradlew publishLayout` copies it into the upload layout with the Minecraft version in the filename:

```text
.publishing/releases/nows/<nows-version>/<minecraft-version>/installers/NowsInstaller-offline-<nows-version>-mc-<minecraft-version>.jar
```

Build another offline installer by selecting a different supported version:

```bash
./gradlew -Pminecraft_version=1.20.1 publishLayout
```

## Offline local testing

Use `--offline` to install from files already on disk. In offline mode the installer never downloads the manifest or artifacts:

```bash
java -jar build/libs/NowsInstaller-cli-<version>.jar \
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
