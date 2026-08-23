package space.nows.mcnows.installer;

import space.nows.mcnows.remap.MinecraftJarRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Internet-first Nows installer.
 *
 * <p>The release manifest decides which artifacts are downloaded and which are
 * copied out of the installer JAR. This intentionally keeps installer policy out
 * of nows-core.</p>
 */
public final class NowsInstaller {
    private static final String DEFAULTS_RESOURCE = "/META-INF/nows/installer/defaults.properties";
    private static final String USER_AGENT = "NowsInstaller/" + defaultNowsVersion();
    private static final int MAX_REDIRECTS = 5;
    private static final Logger LOG = logger(NowsInstaller.class);

    private NowsInstaller() {}

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        install(options, new InstallerListener() {
            @Override
            public void log(String message) {
                LOG.info(message);
            }
        });
    }

    static Logger logger(Class<?> type) {
        Logger logger = Logger.getLogger(type.getName());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.INFO);
        if (!hasNowsHandler(logger)) {
            ConsoleHandler handler = new ConsoleHandler();
            handler.setLevel(Level.INFO);
            handler.setFormatter(new CompactInstallerFormatter());
            logger.addHandler(handler);
        }
        return logger;
    }

    private static boolean hasNowsHandler(Logger logger) {
        for (Handler handler : logger.getHandlers()) {
            if (handler.getFormatter() instanceof CompactInstallerFormatter) {
                return true;
            }
        }
        return false;
    }

    static void install(Options options, InstallerListener listener) throws Exception {
        String manifestLocation = options.manifestLocation();

        listener.log("[NowsInstaller] launcher: " + options.launcher);
        listener.log("[NowsInstaller] minecraft dir: " + options.minecraftDir);
        if (options.instanceDir != null) {
            listener.log("[NowsInstaller] instance dir: " + options.instanceDir);
        }
        listener.log("[NowsInstaller] manifest: " + manifestLocation);
        Properties manifest = loadProperties(options, manifestLocation);
        verifyManifest(manifest, options);

        List<InstalledLibrary> launcherLibraries = new ArrayList<>();
        int count = Integer.parseInt(manifest.getProperty("artifact.count", "0"));
        for (int i = 0; i < count; i++) {
            InstalledLibrary library = installArtifact(options, manifest, i);
            launcherLibraries.add(library);
            listener.log("[NowsInstaller] installed " + library.coordinate());
        }
        installMods(options, manifest, listener);

        String profile = installVersionJson(options, manifest, launcherLibraries);
        listener.log("[NowsInstaller] version json: "
                + options.minecraftDir.resolve("versions").resolve(profile).resolve(profile + ".json"));
        if (options.writesLauncherProfile()) {
            installLauncherProfile(options, profile);
            listener.log("[NowsInstaller] launcher profiles: " + options.minecraftDir.resolve("launcher_profiles.json"));
        } else if (options.writesMultiMcPack()) {
            installMultiMcPack(options, profile, launcherLibraries);
            listener.log("[NowsInstaller] instance pack: " + options.instanceDir.resolve("mmc-pack.json"));
            listener.log("[NowsInstaller] instance patch: " + options.instanceDir.resolve("patches").resolve("space.nows.mcnows.json"));
        } else {
            installInstanceReference(options, profile);
            listener.log("[NowsInstaller] instance reference: "
                    + profileGameDirectory(options).resolve("nows").resolve("launcher-profile.properties"));
            listener.log("[NowsInstaller] " + options.launcher
                    + " instance install placed files in the selected game folder; native app launch integration "
                    + "requires that launcher to support custom Minecraft version ids.");
            listener.log("[NowsInstaller] choose version id in your launcher if needed: " + profile);
        }
        listener.log("[NowsInstaller] default game dir: " + options.minecraftDir);
        listener.log("[NowsInstaller] profile game dir: " + profileGameDirectory(options));
        listener.log("[NowsInstaller] game mods dir: " + minecraftModsDirectory(options));
        listener.log("[NowsInstaller] optional profile mods dir: " + profileModsDirectory(options));
        listener.log("[NowsInstaller] Installed " + profile);
    }

    private static String profileId(Options options) {
        return "nows-" + options.nowsVersion + "-" + options.minecraftVersion;
    }

    private static InstalledLibrary installArtifact(Options options, Properties manifest, int index) throws Exception {
        String prefix = "artifact." + index + ".";
        String coordinate = required(manifest, prefix + "coordinate");
        String relativePath = required(manifest, prefix + "path").replace('\\', '/');
        String source = manifest.getProperty(prefix + "source", "internet").trim();
        String expectedSha256 = manifest.getProperty(prefix + "sha256", "").trim();
        String sourceUrl = manifest.getProperty(prefix + "url", "").trim();
        String launcherSourceUrl = "";
        if (!options.offline && !options.embeddedArtifacts && source.equals("internet")) {
            launcherSourceUrl = internetArtifactUrl(manifest, prefix, relativePath, coordinate, sourceUrl);
        }

        Path destination = options.minecraftDir.resolve("libraries").resolve(relativePath);
        Files.createDirectories(destination.getParent());

        if (Files.isRegularFile(destination) && !expectedSha256.isEmpty()
                && sha256(destination).equalsIgnoreCase(expectedSha256)) {
            return new InstalledLibrary(coordinate, relativePath, launcherSourceUrl, destination);
        }

        if (source.equals("embedded")) {
            copyEmbedded(required(manifest, prefix + "resource"), destination);
        } else if (options.embeddedArtifacts) {
            copyEmbedded("/META-INF/nows/offline-libraries/" + relativePath, destination);
        } else if (options.offline) {
            copyLocalArtifact(options, manifest, prefix, relativePath, destination);
        } else if (source.equals("internet")) {
            launcherSourceUrl = downloadInternetArtifact(manifest, prefix, relativePath, coordinate, sourceUrl, destination);
        } else if (source.equals("local")) {
            copyLocalArtifact(options, manifest, prefix, relativePath, destination);
        } else {
            throw new IllegalArgumentException("Unknown artifact source '" + source + "' for " + coordinate);
        }

        if (!expectedSha256.isEmpty()) {
            String actual = sha256(destination);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                Files.deleteIfExists(destination);
                throw new IOException("SHA-256 mismatch for " + coordinate + ": expected "
                        + expectedSha256 + ", got " + actual);
            }
        }

        return new InstalledLibrary(coordinate, relativePath, launcherSourceUrl, destination);
    }

    private static String downloadInternetArtifact(
            Properties manifest,
            String prefix,
            String relativePath,
            String coordinate,
            String sourceUrl,
            Path destination
    ) throws Exception {
        String fallbackUrl = mavenFallbackUrl(manifest, prefix, relativePath);
        if (!isBlank(sourceUrl)) {
            try {
                downloadInternet(sourceUrl, destination);
                return sourceUrl;
            } catch (IOException primaryFailure) {
                if (isBlank(fallbackUrl) || fallbackUrl.equals(sourceUrl)) {
                    throw primaryFailure;
                }
                downloadInternet(fallbackUrl, destination);
                return fallbackUrl;
            }
        }
        if (isBlank(fallbackUrl)) {
            throw new IllegalArgumentException("Missing URL for internet artifact: " + coordinate);
        }
        downloadInternet(fallbackUrl, destination);
        return fallbackUrl;
    }

    private static String internetArtifactUrl(
            Properties manifest,
            String prefix,
            String relativePath,
            String coordinate,
            String sourceUrl
    ) {
        if (!isBlank(sourceUrl)) {
            parseInternetUri(sourceUrl);
            return sourceUrl;
        }
        String fallbackUrl = mavenFallbackUrl(manifest, prefix, relativePath);
        if (isBlank(fallbackUrl)) {
            throw new IllegalArgumentException("Missing URL for internet artifact: " + coordinate);
        }
        parseInternetUri(fallbackUrl);
        return fallbackUrl;
    }

    private static String mavenFallbackUrl(Properties manifest, String prefix, String relativePath) {
        String explicit = manifest.getProperty(prefix + "mavenUrl", "").trim();
        if (!isBlank(explicit)) {
            return explicit;
        }
        String base = manifest.getProperty("mavenBaseUrl", "https://repo1.maven.org/maven2").trim();
        if (isBlank(base)) {
            return "";
        }
        if (!base.endsWith("/")) {
            base += "/";
        }
        return base + relativePath;
    }

    private static void copyLocalArtifact(
            Options options,
            Properties manifest,
            String prefix,
            String relativePath,
            Path destination
    ) throws IOException {
        Path source = localArtifactPath(options, manifest.getProperty(prefix + "file", "").trim(), relativePath);
        if (!Files.isRegularFile(source)) {
            throw new IOException("Offline artifact missing: " + source);
        }
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void installMods(Options options, Properties manifest, InstallerListener listener) throws Exception {
        int count = Integer.parseInt(manifest.getProperty("mod.count", "0"));
        if (count <= 0) {
            return;
        }
        Path modsDir = minecraftModsDirectory(options);
        Files.createDirectories(modsDir);
        for (int i = 0; i < count; i++) {
            String fileName = installMod(options, manifest, i, modsDir);
            listener.log("[NowsInstaller] installed companion mod " + fileName);
        }
    }

    private static String installMod(Options options, Properties manifest, int index, Path modsDir) throws Exception {
        String prefix = "mod." + index + ".";
        String fileName = required(manifest, prefix + "file");
        if (fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("Companion mod file must be a file name only: " + fileName);
        }
        String source = manifest.getProperty(prefix + "source", "internet").trim();
        String sourceUrl = manifest.getProperty(prefix + "url", "").trim();
        String expectedSha256 = manifest.getProperty(prefix + "sha256", "").trim();

        Path destination = modsDir.resolve(fileName);
        if (Files.isRegularFile(destination) && !expectedSha256.isEmpty()
                && sha256(destination).equalsIgnoreCase(expectedSha256)) {
            return fileName;
        }

        if (source.equals("embedded")) {
            copyEmbedded(required(manifest, prefix + "resource"), destination);
        } else if (options.embeddedArtifacts) {
            copyEmbedded("/META-INF/nows/offline-mods/" + fileName, destination);
        } else if (options.offline) {
            copyLocalMod(options, manifest, prefix, fileName, destination);
        } else if (source.equals("internet")) {
            if (isBlank(sourceUrl)) {
                throw new IllegalArgumentException("Missing URL for companion mod: " + fileName);
            }
            downloadInternet(sourceUrl, destination);
        } else if (source.equals("local")) {
            copyLocalMod(options, manifest, prefix, fileName, destination);
        } else {
            throw new IllegalArgumentException("Unknown companion mod source '" + source + "' for " + fileName);
        }

        if (!expectedSha256.isEmpty()) {
            String actual = sha256(destination);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                Files.deleteIfExists(destination);
                throw new IOException("SHA-256 mismatch for companion mod " + fileName + ": expected "
                        + expectedSha256 + ", got " + actual);
            }
        }
        return fileName;
    }

    private static void copyLocalMod(
            Options options,
            Properties manifest,
            String prefix,
            String fileName,
            Path destination
    ) throws IOException {
        String manifestFile = manifest.getProperty(prefix + "localFile", "").trim();
        Path source;
        if (isBlank(manifestFile)) {
            source = options.artifactDir().resolve("mods").resolve(fileName).normalize();
        } else {
            Path path = Paths.get(manifestFile);
            source = path.isAbsolute() ? path.normalize() : options.artifactDir().resolve(path).normalize();
        }
        if (!Files.isRegularFile(source)) {
            throw new IOException("Offline companion mod missing: " + source);
        }
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String installVersionJson(
            Options options,
            Properties manifest,
            List<InstalledLibrary> libraries
    ) throws Exception {
        String profile = profileId(options);
        Instant now = Instant.now();
        Path versionDir = options.minecraftDir.resolve("versions").resolve(profile);
        Files.createDirectories(versionDir);
        Path profileJar = versionDir.resolve(profile + ".jar");
        boolean hasProfileJar = prepareMinecraftClientJar(options, profileJar);
        StringBuilder json = new StringBuilder();

        json.append("{\n");
        json.append("  \"id\": ").append(quote(profile)).append(",\n");
        json.append("  \"inheritsFrom\": ").append(quote(options.minecraftVersion)).append(",\n");
        MinecraftJavaRuntime javaRuntime = minecraftJavaRuntime(options.minecraftVersion);
        json.append("  \"javaVersion\": {\"component\":")
                .append(quote(javaRuntime.component))
                .append(",\"majorVersion\":")
                .append(javaRuntime.majorVersion)
                .append("},\n");
        if (!hasProfileJar) {
            json.append("  \"jar\": ").append(quote(options.minecraftVersion)).append(",\n");
        } else {
            json.append("  \"downloads\": {\"client\":{");
            json.append("\"sha1\":").append(quote(sha1(profileJar))).append(',');
            json.append("\"size\":").append(Files.size(profileJar));
            json.append("}},\n");
        }
        json.append("  \"mainClass\": ")
                .append(quote(manifest.getProperty("mainClass", "space.nows.mcnows.runtime.NowsLauncher")))
                .append(",\n");
        json.append("  \"type\": \"release\",\n");
        json.append("  \"time\": ").append(quote(now.toString())).append(",\n");
        json.append("  \"releaseTime\": ").append(quote(now.toString())).append(",\n");
        json.append("  \"libraries\": [\n");

        List<InstalledLibrary> launcherVisibleLibraries = launcherVisibleLibraries(libraries);
        for (int i = 0; i < launcherVisibleLibraries.size(); i++) {
            InstalledLibrary lib = launcherVisibleLibraries.get(i);
            json.append("    {\"name\":").append(quote(lib.coordinate())).append(",\"downloads\":{\"artifact\":{");
            json.append("\"path\":").append(quote(lib.relativePath())).append(',');
            json.append("\"sha1\":").append(quote(sha1(lib.file()))).append(',');
            json.append("\"size\":").append(Files.size(lib.file()));
            if (!isBlank(lib.sourceUrl())) {
                json.append(",\"url\":").append(quote(lib.sourceUrl()));
            }
            json.append("}}}");
            if (i + 1 < launcherVisibleLibraries.size()) json.append(',');
            json.append('\n');
        }

        json.append("  ],\n");
        json.append("  \"arguments\": {\n");
        json.append("    \"game\": [\"--nowsMinecraftVersion\", ")
                .append(quote(options.minecraftVersion)).append("],\n");
        json.append("    \"jvm\": [");
        json.append(quote("-Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector"));
        json.append(", ").append(quote("-Dnows.domain=nows.space"));
        InstalledLibrary minecraftAdapter = minecraftAdapterLibrary(libraries);
        if (minecraftAdapter != null) {
            json.append(", ").append(quote("-Dnows.minecraftAdapterPath=" + minecraftAdapter.file()));
        }
        json.append("]\n");
        json.append("  }\n");
        json.append("}\n");

        writeString(versionDir.resolve(profile + ".json"), json.toString());
        return profile;
    }

    static MinecraftJavaRuntime minecraftJavaRuntime(String minecraftVersion) {
        if (minecraftVersion.startsWith("26.")) {
            return new MinecraftJavaRuntime("java-runtime-epsilon", 25);
        }
        if (minecraftVersion.startsWith("1.21.") || minecraftVersion.equals("1.20.6")) {
            return new MinecraftJavaRuntime("java-runtime-delta", 21);
        }
        return new MinecraftJavaRuntime("java-runtime-gamma", 17);
    }

    static final class MinecraftJavaRuntime {
        final String component;
        final int majorVersion;

        MinecraftJavaRuntime(String component, int majorVersion) {
            this.component = component;
            this.majorVersion = majorVersion;
        }
    }

    private static List<InstalledLibrary> launcherVisibleLibraries(List<InstalledLibrary> libraries) {
        List<InstalledLibrary> visible = new ArrayList<>();
        for (InstalledLibrary library : libraries) {
            if (!library.isMinecraftAdapter()) {
                visible.add(library);
            }
        }
        return visible;
    }

    private static InstalledLibrary minecraftAdapterLibrary(List<InstalledLibrary> libraries) {
        for (InstalledLibrary library : libraries) {
            if (library.isMinecraftAdapter()) {
                return library;
            }
        }
        return null;
    }

    private static boolean prepareMinecraftClientJar(Options options, Path target) throws Exception {
        if (Files.isRegularFile(target) && MinecraftJarRemapper.containsNamedMinecraft(target)) {
            return true;
        }

        Path inherited = inheritedVersionJar(options);
        if (Files.isRegularFile(inherited)) {
            return prepareMinecraftClientJar(options, inherited, null, target);
        }

        if (options.offline) {
            return false;
        }

        Map<String, Object> versionJson = resolveMojangVersion(options.minecraftVersion);
        DownloadSpec client = DownloadSpec.from(jsonObject(
                jsonObject(versionJson.get("downloads"), "downloads").get("client"),
                "downloads.client"));
        Path cached = minecraftCacheDir(options).resolve("client-runtime.jar");
        downloadVerified(client, cached);
        return prepareMinecraftClientJar(options, cached, versionJson, target);
    }

    private static boolean prepareMinecraftClientJar(
            Options options,
            Path source,
            Map<String, Object> versionJson,
            Path target
    ) throws Exception {
        Files.createDirectories(target.getParent());
        if (MinecraftJarRemapper.containsNamedMinecraft(source)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            return true;
        }

        if (options.offline) {
            throw new IOException("Minecraft " + options.minecraftVersion
                    + " uses an obfuscated client jar. Preparing a Mojang-named local profile jar requires "
                    + "Mojang's official client mappings; run the internet installer once for this Minecraft version.");
        }

        if (versionJson == null) {
            versionJson = resolveMojangVersion(options.minecraftVersion);
        }
        Object mappingObject = jsonObject(versionJson.get("downloads"), "downloads").get("client_mappings");
        if (mappingObject == null) {
            throw new IOException("Minecraft " + options.minecraftVersion + " has no official client mappings");
        }

        DownloadSpec mappings = DownloadSpec.from(jsonObject(mappingObject, "downloads.client_mappings"));
        Path mappingFile = minecraftCacheDir(options).resolve("client_mappings.txt");
        downloadVerified(mappings, mappingFile);
        MinecraftJarRemapper.remapOfficial(source, mappingFile, target);
        if (!MinecraftJarRemapper.containsNamedMinecraft(target)) {
            Files.deleteIfExists(target);
            throw new IOException("Prepared Minecraft client jar is not Mojang-named: " + target);
        }
        return true;
    }

    private static Path inheritedVersionJar(Options options) {
        return options.minecraftDir
                .resolve("versions")
                .resolve(options.minecraftVersion)
                .resolve(options.minecraftVersion + ".jar");
    }

    private static Path minecraftCacheDir(Options options) throws IOException {
        Path dir = options.minecraftDir
                .resolve("nows")
                .resolve("cache")
                .resolve("minecraft")
                .resolve(options.minecraftVersion);
        Files.createDirectories(dir);
        return dir;
    }

    @SuppressWarnings("unchecked")
    private static void installLauncherProfile(Options options, String versionProfile) throws IOException {
        Path profilesFile = options.minecraftDir.resolve("launcher_profiles.json");
        Map<String, Object> root;
        if (Files.isRegularFile(profilesFile)) {
            String json = readString(profilesFile);
            Object parsed = Json.parse(json);
            if (!(parsed instanceof Map<?, ?>)) {
                throw new IOException("Minecraft launcher_profiles.json root is not a JSON object: " + profilesFile);
            }
            root = (Map<String, Object>) parsed;
            backupLauncherProfiles(profilesFile);
        } else {
            root = new LinkedHashMap<>();
        }

        Object existingProfiles = root.get("profiles");
        Map<String, Object> profiles;
        if (existingProfiles == null) {
            profiles = new LinkedHashMap<>();
            root.put("profiles", profiles);
        } else if (existingProfiles instanceof Map<?, ?>) {
            profiles = (Map<String, Object>) existingProfiles;
        } else {
            throw new IOException("Minecraft launcher_profiles.json profiles field is not a JSON object: " + profilesFile);
        }

        profiles.put(versionProfile, launcherProfile(options, versionProfile));
        Files.createDirectories(options.minecraftDir);
        writeString(profilesFile, Json.write(root));
    }

    private static Map<String, Object> launcherProfile(Options options, String versionProfile) throws IOException {
        Instant now = Instant.now();
        Path gameDir = profileGameDirectory(options);
        Files.createDirectories(minecraftModsDirectory(options));
        Files.createDirectories(gameDir.resolve("mods"));
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Nows " + options.nowsVersion + " - Minecraft " + options.minecraftVersion);
        profile.put("type", "custom");
        profile.put("created", now.toString());
        profile.put("lastUsed", now.toString());
        profile.put("lastVersionId", versionProfile);
        if (options.usesProfileGameDir()) {
            profile.put("gameDir", gameDir.toString());
        }
        String icon = launcherIcon();
        if (icon != null) {
            profile.put("icon", icon);
        }
        return profile;
    }

    private static Path profileGameDirectory(Options options) {
        if (options.gameDir != null) {
            return options.gameDir;
        }
        if (options.instanceDir != null) {
            return instanceGameDirectory(options.launcher, options.instanceDir);
        }
        return options.minecraftDir.resolve("nows").resolve("profiles").resolve(profileId(options));
    }

    static Path instanceGameDirectory(String launcher, Path instanceDir) {
        Path normalized = instanceDir.toAbsolutePath().normalize();
        if (looksLikeGameDirectory(normalized)) {
            return normalized;
        }

        Path dotMinecraft = normalized.resolve(".minecraft");
        if (looksLikeGameDirectory(dotMinecraft)) {
            return dotMinecraft;
        }

        Path minecraft = normalized.resolve("minecraft");
        if (looksLikeGameDirectory(minecraft)) {
            return minecraft;
        }

        String kind = normalizeLauncher(launcher);
        if (kind.equals("prism") || kind.equals("multimc")) {
            return dotMinecraft;
        }
        return normalized;
    }

    static boolean looksLikeGameDirectory(Path path) {
        return Files.isDirectory(path.resolve("mods"))
                || Files.isDirectory(path.resolve("config"))
                || Files.isDirectory(path.resolve("saves"))
                || Files.isRegularFile(path.resolve("options.txt"))
                || looksLikeMinecraftDir(path);
    }

    private static Path minecraftModsDirectory(Options options) {
        if (options.usesProfileGameDir()) {
            return profileModsDirectory(options);
        }
        return options.minecraftDir.resolve("mods");
    }

    private static Path profileModsDirectory(Options options) {
        return profileGameDirectory(options).resolve("mods");
    }

    private static void installInstanceReference(Options options, String versionProfile) throws IOException {
        Path reference = profileGameDirectory(options).resolve("nows").resolve("launcher-profile.properties");
        Files.createDirectories(reference.getParent());
        Properties properties = new Properties();
        properties.setProperty("launcher", options.launcher);
        properties.setProperty("profile", versionProfile);
        properties.setProperty("nows.version", options.nowsVersion);
        properties.setProperty("minecraft.version", options.minecraftVersion);
        properties.setProperty("minecraftDir", options.minecraftDir.toString());
        if (options.instanceDir != null) {
            properties.setProperty("instanceDir", options.instanceDir.toString());
        }
        try (OutputStream output = Files.newOutputStream(reference)) {
            properties.store(output, "Generated by NowsInstaller");
        }
    }

    private static void installMultiMcPack(
            Options options,
            String versionProfile,
            List<InstalledLibrary> libraries
    ) throws IOException {
        Path instanceDir = options.instanceDir;
        Files.createDirectories(instanceDir);
        Path patchesDir = instanceDir.resolve("patches");
        Files.createDirectories(patchesDir);
        copyMultiMcLibraries(instanceDir, libraries);
        writeMultiMcPatch(options, versionProfile, libraries, patchesDir.resolve("space.nows.mcnows.json"));
        writeMultiMcPack(options, instanceDir.resolve("mmc-pack.json"));
        installInstanceReference(options, versionProfile);
    }

    private static void copyMultiMcLibraries(Path instanceDir, List<InstalledLibrary> libraries) throws IOException {
        Path localLibraries = instanceDir.resolve("libraries");
        Files.createDirectories(localLibraries);
        for (InstalledLibrary library : libraries) {
            Files.copy(library.file(), localLibraries.resolve(library.file().getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeMultiMcPatch(
            Options options,
            String versionProfile,
            List<InstalledLibrary> libraries,
            Path patchFile
    ) throws IOException {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("formatVersion", 1);
        patch.put("uid", "space.nows.mcnows");
        patch.put("name", "Nows");
        patch.put("version", options.nowsVersion);
        patch.put("type", "release");
        patch.put("releaseTime", Instant.now().toString());
        Map<String, Object> minecraftRequirement = new LinkedHashMap<>();
        minecraftRequirement.put("uid", "net.minecraft");
        minecraftRequirement.put("equals", options.minecraftVersion);
        List<Object> requirements = new ArrayList<>();
        requirements.add(minecraftRequirement);
        patch.put("requires", requirements);
        patch.put("mainClass", "space.nows.mcnows.runtime.NowsLauncher");
        List<Object> jvmArgs = new ArrayList<>();
        jvmArgs.add("-Dnows.minecraftVersion=" + options.minecraftVersion);
        jvmArgs.add("-Dnows.profileId=" + versionProfile);
        jvmArgs.add("-Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector");
        jvmArgs.add("-Dnows.domain=nows.space");
        InstalledLibrary minecraftAdapter = minecraftAdapterLibrary(libraries);
        if (minecraftAdapter != null) {
            jvmArgs.add("-Dnows.minecraftAdapterPath="
                    + options.instanceDir.resolve("libraries").resolve(minecraftAdapter.file().getFileName()));
        }
        patch.put("+jvmArgs", jvmArgs);

        List<Object> patchLibraries = new ArrayList<>();
        for (InstalledLibrary library : libraries) {
            if (library.isMinecraftAdapter()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", library.coordinate());
            entry.put("MMC-hint", "local");
            patchLibraries.add(entry);
        }
        patch.put("+libraries", patchLibraries);
        writeString(patchFile, Json.write(patch));
    }

    @SuppressWarnings("unchecked")
    private static void writeMultiMcPack(Options options, Path packFile) throws IOException {
        Map<String, Object> pack;
        if (Files.isRegularFile(packFile)) {
            Object parsed = Json.parse(readString(packFile));
            if (!(parsed instanceof Map<?, ?>)) {
                throw new IOException("mmc-pack.json root is not a JSON object: " + packFile);
            }
            pack = (Map<String, Object>) parsed;
            backupLauncherProfiles(packFile);
        } else {
            pack = new LinkedHashMap<>();
            pack.put("formatVersion", 1);
        }

        Object componentsObject = pack.get("components");
        List<Object> components;
        if (componentsObject == null) {
            components = new ArrayList<>();
            pack.put("components", components);
        } else if (componentsObject instanceof List<?>) {
            components = new ArrayList<>((List<Object>) componentsObject);
            pack.put("components", components);
        } else {
            throw new IOException("mmc-pack.json components field is not a JSON array: " + packFile);
        }

        upsertMultiMcComponent(components, minecraftComponent(options));
        upsertMultiMcComponent(components, nowsComponent(options));
        writeString(packFile, Json.write(pack));
    }

    @SuppressWarnings("unchecked")
    private static void upsertMultiMcComponent(List<Object> components, Map<String, Object> replacement) {
        String uid = (String) replacement.get("uid");
        for (int i = 0; i < components.size(); i++) {
            Object value = components.get(i);
            if (value instanceof Map<?, ?>) {
                Map<String, Object> component = (Map<String, Object>) value;
                if (uid.equals(component.get("uid"))) {
                    components.set(i, replacement);
                    return;
                }
            }
        }
        components.add(replacement);
    }

    private static Map<String, Object> minecraftComponent(Options options) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("cachedName", "Minecraft");
        component.put("cachedVersion", options.minecraftVersion);
        component.put("important", true);
        component.put("uid", "net.minecraft");
        component.put("version", options.minecraftVersion);
        return component;
    }

    private static Map<String, Object> nowsComponent(Options options) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("cachedName", "Nows");
        component.put("cachedVersion", options.nowsVersion);
        component.put("uid", "space.nows.mcnows");
        component.put("version", options.nowsVersion);
        return component;
    }

    private static void backupLauncherProfiles(Path profilesFile) throws IOException {
        String stamp = String.valueOf(System.currentTimeMillis());
        Path backup = profilesFile.resolveSibling(profilesFile.getFileName() + ".nows-backup-" + stamp);
        Files.copy(profilesFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static String launcherIcon() throws IOException {
        String resource = "/META-INF/nows/branding/nows.png";
        try (InputStream input = NowsInstaller.class.getResourceAsStream(resource)) {
            if (input == null) {
                return null;
            }
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(readAllBytes(input));
        }
    }

    private static Properties loadProperties(Options options, String location) throws Exception {
        if (options.embeddedManifestResource != null) {
            return loadEmbeddedProperties(options.embeddedManifestResource);
        }
        if (options.offline || isLocalLocation(location)) {
            Path path = localManifestPath(options, location);
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            }
            return properties;
        }
        return downloadProperties(parseInternetUri(location));
    }

    private static Properties loadEmbeddedProperties(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = NowsInstaller.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Embedded installer manifest missing: " + resource);
            }
            properties.load(input);
        }
        return properties;
    }

    private static Properties downloadProperties(URI uri) throws Exception {
        HttpURLConnection connection = openConnection(uri);
        Properties properties = new Properties();
        try (InputStream input = connection.getInputStream()) {
            properties.load(input);
        } finally {
            connection.disconnect();
        }
        return properties;
    }

    private static boolean isLocalLocation(String location) {
        if (!hasUriScheme(location)) {
            return true;
        }
        String scheme = URI.create(location).getScheme();
        return scheme == null || scheme.equals("file");
    }

    private static Path localManifestPath(Options options, String location) {
        if (!hasUriScheme(location)) {
            return Paths.get(location).toAbsolutePath().normalize();
        }
        URI uri = URI.create(location);
        if (!uri.getScheme().equals("file")) {
            if (options.offline) {
                throw new IllegalArgumentException("Offline install requires a local --manifest file");
            }
            throw new IllegalArgumentException("Not a local manifest location: " + location);
        }
        return Paths.get(uri).toAbsolutePath().normalize();
    }

    private static boolean hasUriScheme(String location) {
        for (int i = 0; i < location.length(); i++) {
            char c = location.charAt(i);
            if (c == ':') {
                return i > 0;
            }
            if (!(Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.')) {
                return false;
            }
        }
        return false;
    }

    private static Path localArtifactPath(Options options, String manifestFile, String relativePath) {
        if (!isBlank(manifestFile)) {
            Path path = Paths.get(manifestFile);
            if (path.isAbsolute()) {
                return path.normalize();
            }
            return options.artifactDir().resolve(path).normalize();
        }
        return options.artifactDir().resolve(relativePath).normalize();
    }

    private static void downloadInternet(String location, Path destination) throws Exception {
        download(parseInternetUri(location), destination);
    }

    private static void download(URI uri, Path destination) throws Exception {
        Path temp = destination.resolveSibling(destination.getFileName() + ".part");
        Files.deleteIfExists(temp);
        HttpURLConnection connection = openConnection(uri);
        try (InputStream input = connection.getInputStream()) {
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        } finally {
            connection.disconnect();
        }
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Map<String, Object> resolveMojangVersion(String id) throws Exception {
        Map<String, Object> manifest = downloadJsonObject("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        List<Object> versions = jsonArray(manifest.get("versions"), "versions");
        for (Object value : versions) {
            Map<String, Object> version = jsonObject(value, "versions[]");
            if (id.equals(jsonString(version.get("id"), "versions[].id"))) {
                return downloadJsonObject(jsonString(version.get("url"), "versions[].url"));
            }
        }
        throw new IOException("Minecraft version not found in Mojang metadata: " + id);
    }

    private static Map<String, Object> downloadJsonObject(String location) throws Exception {
        HttpURLConnection connection = openConnection(parseInternetUri(location));
        try (InputStream input = connection.getInputStream()) {
            String json = new String(readAllBytes(input), StandardCharsets.UTF_8);
            return jsonObject(Json.parse(json), location);
        } finally {
            connection.disconnect();
        }
    }

    private static void downloadVerified(DownloadSpec spec, Path destination) throws Exception {
        if (isValidDownload(destination, spec)) {
            return;
        }
        download(spec.uri, destination);
        if (!isValidDownload(destination, spec)) {
            Files.deleteIfExists(destination);
            throw new IOException("Minecraft download failed SHA-1/size validation: " + spec.uri);
        }
    }

    private static boolean isValidDownload(Path path, DownloadSpec spec) throws IOException {
        return Files.isRegularFile(path)
                && Files.size(path) == spec.size
                && sha1(path).equalsIgnoreCase(spec.sha1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonObject(Object value, String name) throws IOException {
        if (value instanceof Map<?, ?>) {
            return (Map<String, Object>) value;
        }
        throw new IOException("Expected JSON object for " + name);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> jsonArray(Object value, String name) throws IOException {
        if (value instanceof List<?>) {
            return (List<Object>) value;
        }
        throw new IOException("Expected JSON array for " + name);
    }

    private static String jsonString(Object value, String name) throws IOException {
        if (value instanceof String) {
            return (String) value;
        }
        throw new IOException("Expected JSON string for " + name);
    }

    private static long jsonLong(Object value, String name) throws IOException {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        throw new IOException("Expected JSON number for " + name);
    }

    private static HttpURLConnection openConnection(URI uri) throws IOException {
        return openConnection(uri, 0);
    }

    private static HttpURLConnection openConnection(URI uri, int redirects) throws IOException {
        validateInternetUri(uri);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/octet-stream, text/plain, */*");
        int status = connection.getResponseCode();
        if (isRedirect(status)) {
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (redirects >= MAX_REDIRECTS) {
                throw new IOException("Too many redirects while downloading " + uri);
            }
            if (isBlank(location)) {
                throw new IOException("HTTP " + status + " redirect without Location for " + uri);
            }
            URI next = uri.resolve(location);
            validateRedirect(uri, next);
            return openConnection(next, redirects + 1);
        }
        if (status / 100 != 2) {
            String details = readErrorDetails(connection);
            connection.disconnect();
            throw new IOException("HTTP " + status + " for " + uri + details);
        }
        return connection;
    }

    private static URI parseInternetUri(String location) {
        try {
            URI uri = new URI(location);
            validateInternetUri(uri);
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid download URL: " + location, e);
        }
    }

    private static void validateInternetUri(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("Download URL must include https://: " + uri);
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        if ("http".equalsIgnoreCase(scheme) && isLocalHttpHost(uri.getHost())) {
            return;
        }
        throw new IllegalArgumentException("Refusing non-HTTPS download URL: " + uri);
    }

    private static boolean isLocalHttpHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1");
    }

    private static void validateRedirect(URI from, URI to) {
        validateInternetUri(to);
        if ("https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(to.getScheme())) {
            throw new IllegalArgumentException("Refusing HTTPS downgrade redirect from " + from + " to " + to);
        }
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307
                || status == 308;
    }

    private static String readErrorDetails(HttpURLConnection connection) {
        InputStream error = connection.getErrorStream();
        if (error == null) {
            return "";
        }
        try (InputStream input = error) {
            String body = new String(readAllBytes(input), StandardCharsets.UTF_8).trim();
            if (body.isEmpty()) {
                return "";
            }
            if (body.length() > 240) {
                body = body.substring(0, 240) + "...";
            }
            return ": " + body.replace('\n', ' ').replace('\r', ' ');
        } catch (IOException ignored) {
            return "";
        }
    }

    private static void copyEmbedded(String resource, Path destination) throws IOException {
        try (InputStream input = NowsInstaller.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Embedded installer payload missing: " + resource);
            }
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void verifyManifest(Properties properties, Options options) {
        if (!"1".equals(properties.getProperty("format"))) {
            throw new IllegalArgumentException("Unsupported Nows install manifest format");
        }
        if (!options.nowsVersion.equals(required(properties, "nows.version"))) {
            throw new IllegalArgumentException("Manifest Nows version mismatch");
        }
        if (!options.minecraftVersion.equals(required(properties, "minecraft.version"))) {
            throw new IllegalArgumentException("Manifest Minecraft version mismatch");
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || isBlank(value)) {
            throw new IllegalArgumentException("Missing install manifest property: " + key);
        }
        return value.trim();
    }

    static String defaultNowsVersion() {
        return defaultProperty("nows.version", "0.0.0");
    }

    static String defaultMinecraftVersion() {
        return defaultProperty("minecraft.version", "26.2");
    }

    static List<String> supportedMinecraftVersions() {
        String configured = defaultProperty("minecraft.versions", "");
        List<String> versions = new ArrayList<>();
        if (!isBlank(configured)) {
            String[] parts = configured.split(",");
            for (String part : parts) {
                String version = part.trim();
                if (!version.isEmpty() && !versions.contains(version)) {
                    versions.add(version);
                }
            }
        }
        String fallback = defaultMinecraftVersion();
        if (!versions.contains(fallback)) {
            versions.add(0, fallback);
        }
        return versions;
    }

    static Properties embeddedOfflineManifest() throws IOException {
        return loadEmbeddedProperties("/META-INF/nows/offline/install.properties");
    }

    static Path defaultMinecraftDir() {
        List<Path> candidates = minecraftDirectoryCandidates();
        for (Path candidate : candidates) {
            if (looksLikeMinecraftDir(candidate)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    static List<Path> minecraftDirectoryCandidates() {
        List<Path> candidates = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (!isBlank(appData)) {
                candidates.add(Paths.get(appData, ".minecraft").toAbsolutePath().normalize());
            }
            candidates.add(Paths.get(home, "AppData", "Roaming", ".minecraft").toAbsolutePath().normalize());
            candidates.add(Paths.get(home, ".minecraft").toAbsolutePath().normalize());
            return uniquePaths(candidates);
        }
        if (os.contains("mac")) {
            candidates.add(Paths.get(home, "Library", "Application Support", "minecraft").toAbsolutePath().normalize());
            candidates.add(Paths.get(home, ".minecraft").toAbsolutePath().normalize());
            return uniquePaths(candidates);
        }
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (!isBlank(xdgDataHome)) {
            candidates.add(Paths.get(xdgDataHome, "minecraft").toAbsolutePath().normalize());
        }
        candidates.add(Paths.get(home, ".minecraft").toAbsolutePath().normalize());
        candidates.add(Paths.get(home, ".var", "app", "com.mojang.Minecraft", ".minecraft").toAbsolutePath().normalize());
        return uniquePaths(candidates);
    }

    static boolean looksLikeMinecraftDir(Path path) {
        return Files.isRegularFile(path.resolve("launcher_profiles.json"))
                || Files.isDirectory(path.resolve("versions"))
                || Files.isDirectory(path.resolve("assets"))
                || Files.isDirectory(path.resolve("libraries"));
    }

    static String normalizeLauncher(String launcher) {
        if (isBlank(launcher)) {
            return "official";
        }
        String normalized = launcher.trim().toLowerCase(Locale.ROOT)
                .replace("_", "-")
                .replace(" ", "-");
        if (normalized.equals("minecraft") || normalized.equals("mojang") || normalized.equals("vanilla")) {
            return "official";
        }
        if (normalized.equals("modrinth-app") || normalized.equals("theseus")) {
            return "modrinth";
        }
        if (normalized.equals("curse") || normalized.equals("curseforge-app") || normalized.equals("overwolf")) {
            return "curseforge";
        }
        if (normalized.equals("prismlauncher")) {
            return "prism";
        }
        if (normalized.equals("multi-mc")) {
            return "multimc";
        }
        if (normalized.equals("instance") || normalized.equals("generic-instance")) {
            return "generic";
        }
        if (normalized.equals("official") || normalized.equals("modrinth") || normalized.equals("curseforge")
                || normalized.equals("prism") || normalized.equals("multimc") || normalized.equals("generic")) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported launcher target: " + launcher);
    }

    private static List<Path> uniquePaths(List<Path> paths) {
        List<Path> unique = new ArrayList<>();
        for (Path path : paths) {
            if (!unique.contains(path)) {
                unique.add(path);
            }
        }
        return unique;
    }

    private static String defaultProperty(String key, String fallback) {
        Properties defaults = new Properties();
        try (InputStream input = NowsInstaller.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (input != null) {
                defaults.load(input);
            }
        } catch (IOException ignored) {
            return fallback;
        }
        String value = defaults.getProperty(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String digest(Path path, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return toHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            out[i * 2] = hex[value >>> 4];
            out[i * 2 + 1] = hex[value & 0x0f];
        }
        return new String(out);
    }

    private static void writeString(Path path, String value) throws IOException {
        try (OutputStream output = Files.newOutputStream(path)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readString(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return new String(readAllBytes(input), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String sha256(Path path) throws IOException { return digest(path, "SHA-256"); }
    private static String sha1(Path path) throws IOException { return digest(path, "SHA-1"); }

    private static String quote(String value) {
        return '"' + escape(value) + '"';
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static final class Json {
        private Json() {}

        static Object parse(String json) throws IOException {
            Parser parser = new Parser(json);
            Object value = parser.parseValue();
            parser.skipWhitespace();
            if (!parser.isEnd()) {
                throw parser.error("Unexpected trailing JSON content");
            }
            return value;
        }

        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            writeValue(out, value, 0);
            out.append('\n');
            return out.toString();
        }

        @SuppressWarnings("unchecked")
        private static void writeValue(StringBuilder out, Object value, int indent) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof String) {
                out.append(quote((String) value));
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else if (value instanceof Map<?, ?>) {
                Map<String, Object> map = (Map<String, Object>) value;
                out.append('{');
                if (!map.isEmpty()) {
                    boolean first = true;
                    for (Map.Entry<String, Object> entry : map.entrySet()) {
                        if (first) first = false; else out.append(',');
                        out.append('\n');
                        indent(out, indent + 2);
                        out.append(quote(entry.getKey())).append(": ");
                        writeValue(out, entry.getValue(), indent + 2);
                    }
                    out.append('\n');
                    indent(out, indent);
                }
                out.append('}');
            } else if (value instanceof List<?>) {
                List<Object> list = (List<Object>) value;
                out.append('[');
                if (!list.isEmpty()) {
                    boolean first = true;
                    for (Object item : list) {
                        if (first) first = false; else out.append(',');
                        out.append('\n');
                        indent(out, indent + 2);
                        writeValue(out, item, indent + 2);
                    }
                    out.append('\n');
                    indent(out, indent);
                }
                out.append(']');
            } else {
                out.append(quote(String.valueOf(value)));
            }
        }

        private static void indent(StringBuilder out, int indent) {
            for (int i = 0; i < indent; i++) {
                out.append(' ');
            }
        }

        private static final class Parser {
            private final String json;
            private int index;

            Parser(String json) {
                this.json = json == null ? "" : json;
            }

            boolean isEnd() {
                return index >= json.length();
            }

            Object parseValue() throws IOException {
                skipWhitespace();
                if (isEnd()) {
                    throw error("Unexpected end of JSON");
                }
                char c = json.charAt(index);
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return parseString();
                if (c == 't') return parseLiteral("true", Boolean.TRUE);
                if (c == 'f') return parseLiteral("false", Boolean.FALSE);
                if (c == 'n') return parseLiteral("null", null);
                if (c == '-' || Character.isDigit(c)) return parseNumber();
                throw error("Unexpected JSON token '" + c + "'");
            }

            private Map<String, Object> parseObject() throws IOException {
                expect('{');
                Map<String, Object> object = new LinkedHashMap<>();
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return object;
                }
                while (true) {
                    skipWhitespace();
                    String key = parseString();
                    skipWhitespace();
                    expect(':');
                    object.put(key, parseValue());
                    skipWhitespace();
                    if (peek('}')) {
                        index++;
                        return object;
                    }
                    expect(',');
                }
            }

            private List<Object> parseArray() throws IOException {
                expect('[');
                List<Object> array = new ArrayList<>();
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return array;
                }
                while (true) {
                    array.add(parseValue());
                    skipWhitespace();
                    if (peek(']')) {
                        index++;
                        return array;
                    }
                    expect(',');
                }
            }

            private String parseString() throws IOException {
                expect('"');
                StringBuilder out = new StringBuilder();
                while (!isEnd()) {
                    char c = json.charAt(index++);
                    if (c == '"') {
                        return out.toString();
                    }
                    if (c != '\\') {
                        out.append(c);
                        continue;
                    }
                    if (isEnd()) {
                        throw error("Unterminated JSON escape");
                    }
                    char escaped = json.charAt(index++);
                    switch (escaped) {
                        case '"': out.append('"'); break;
                        case '\\': out.append('\\'); break;
                        case '/': out.append('/'); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u':
                            if (index + 4 > json.length()) {
                                throw error("Incomplete unicode escape");
                            }
                            out.append((char) Integer.parseInt(json.substring(index, index + 4), 16));
                            index += 4;
                            break;
                        default:
                            throw error("Unsupported JSON escape '\\" + escaped + "'");
                    }
                }
                throw error("Unterminated JSON string");
            }

            private Object parseLiteral(String literal, Object value) throws IOException {
                if (!json.startsWith(literal, index)) {
                    throw error("Expected " + literal);
                }
                index += literal.length();
                return value;
            }

            private Number parseNumber() throws IOException {
                int start = index;
                if (peek('-')) index++;
                while (!isEnd() && Character.isDigit(json.charAt(index))) index++;
                boolean floating = false;
                if (!isEnd() && json.charAt(index) == '.') {
                    floating = true;
                    index++;
                    while (!isEnd() && Character.isDigit(json.charAt(index))) index++;
                }
                if (!isEnd() && (json.charAt(index) == 'e' || json.charAt(index) == 'E')) {
                    floating = true;
                    index++;
                    if (!isEnd() && (json.charAt(index) == '+' || json.charAt(index) == '-')) index++;
                    while (!isEnd() && Character.isDigit(json.charAt(index))) index++;
                }
                String number = json.substring(start, index);
                try {
                    return floating ? Double.valueOf(number) : Long.valueOf(number);
                } catch (NumberFormatException e) {
                    throw error("Invalid JSON number " + number);
                }
            }

            private void expect(char expected) throws IOException {
                skipWhitespace();
                if (isEnd() || json.charAt(index) != expected) {
                    throw error("Expected '" + expected + "'");
                }
                index++;
            }

            private boolean peek(char expected) {
                return !isEnd() && json.charAt(index) == expected;
            }

            private void skipWhitespace() {
                while (!isEnd()) {
                    char c = json.charAt(index);
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') index++;
                    else return;
                }
            }

            private IOException error(String message) {
                return new IOException(message + " at character " + index);
            }
        }
    }

    interface InstallerListener {
        void log(String message);
    }

    private static final class CompactInstallerFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            String time = String.format(Locale.ROOT, "%1$tT", record.getMillis());
            String throwable = "";
            if (record.getThrown() != null) {
                throwable = System.lineSeparator() + record.getThrown();
            }
            return "[" + time + "] [" + record.getLevel().getName() + "] "
                    + formatMessage(record) + throwable + System.lineSeparator();
        }
    }

    private static final class InstalledLibrary {
        private final String coordinate;
        private final String relativePath;
        private final String sourceUrl;
        private final Path file;

        InstalledLibrary(String coordinate, String relativePath, String sourceUrl, Path file) {
            this.coordinate = coordinate;
            this.relativePath = relativePath;
            this.sourceUrl = sourceUrl;
            this.file = file;
        }

        String coordinate() { return coordinate; }
        String relativePath() { return relativePath; }
        String sourceUrl() { return sourceUrl; }
        Path file() { return file; }

        boolean isMinecraftAdapter() {
            return coordinate.startsWith("space.nows.mcnows:nows-mc-");
        }
    }

    private static final class DownloadSpec {
        private final URI uri;
        private final String sha1;
        private final long size;

        private DownloadSpec(URI uri, String sha1, long size) {
            this.uri = uri;
            this.sha1 = sha1;
            this.size = size;
        }

        static DownloadSpec from(Map<String, Object> object) throws IOException {
            return new DownloadSpec(
                    parseInternetUri(jsonString(object.get("url"), "download.url")),
                    jsonString(object.get("sha1"), "download.sha1"),
                    jsonLong(object.get("size"), "download.size"));
        }
    }

    static final class Options {
        final String nowsVersion;
        final String minecraftVersion;
        final Path minecraftDir;
        final String manifest;
        final String launcher;
        final Path instanceDir;
        final boolean offline;
        final Path artifactDir;
        final String embeddedManifestResource;
        final boolean embeddedArtifacts;
        final boolean profileGameDir;
        final Path gameDir;

        private Options(
                String nowsVersion,
                String minecraftVersion,
                Path minecraftDir,
                String manifest,
                String launcher,
                Path instanceDir,
                boolean offline,
                Path artifactDir,
                String embeddedManifestResource,
                boolean embeddedArtifacts,
                boolean profileGameDir,
                Path gameDir
        ) {
            this.nowsVersion = nowsVersion;
            this.minecraftVersion = minecraftVersion;
            this.minecraftDir = minecraftDir;
            this.manifest = manifest;
            this.launcher = launcher;
            this.instanceDir = instanceDir;
            this.offline = offline;
            this.artifactDir = artifactDir;
            this.embeddedManifestResource = embeddedManifestResource;
            this.embeddedArtifacts = embeddedArtifacts;
            this.profileGameDir = profileGameDir;
            this.gameDir = gameDir;
        }

        static Options parse(String[] args) {
            String nows = defaultNowsVersion();
            String minecraft = defaultMinecraftVersion();
            String manifest = null;
            String launcher = "official";
            boolean offline = false;
            boolean profileGameDir = false;
            Path artifactDir = null;
            Path minecraftDir = defaultMinecraftDir();
            Path instanceDir = null;
            Path gameDir = null;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--nows".equals(arg)) {
                    nows = requireValue(args, ++i, "--nows");
                } else if ("--minecraft".equals(arg)) {
                    minecraft = requireValue(args, ++i, "--minecraft");
                } else if ("--minecraftDir".equals(arg)) {
                    minecraftDir = Paths.get(requireValue(args, ++i, "--minecraftDir"))
                            .toAbsolutePath().normalize();
                } else if ("--manifest".equals(arg)) {
                    manifest = requireValue(args, ++i, "--manifest");
                } else if ("--launcher".equals(arg)) {
                    launcher = normalizeLauncher(requireValue(args, ++i, "--launcher"));
                } else if ("--instanceDir".equals(arg) || "--instance".equals(arg)) {
                    instanceDir = Paths.get(requireValue(args, ++i, arg))
                            .toAbsolutePath().normalize();
                } else if ("--offline".equals(arg)) {
                    offline = true;
                } else if ("--profileGameDir".equals(arg)) {
                    profileGameDir = true;
                } else if ("--gameDir".equals(arg)) {
                    gameDir = Paths.get(requireValue(args, ++i, "--gameDir"))
                            .toAbsolutePath().normalize();
                    profileGameDir = true;
                } else if ("--artifactDir".equals(arg)) {
                    artifactDir = Paths.get(requireValue(args, ++i, "--artifactDir"))
                            .toAbsolutePath().normalize();
                } else {
                    throw new IllegalArgumentException("Unknown option: " + arg);
                }
            }
            if (offline && manifest == null) {
                throw new IllegalArgumentException("Offline install requires --manifest pointing to a local file");
            }
            launcher = normalizeLauncher(launcher);
            if (!launcher.equals("official") && instanceDir == null) {
                throw new IllegalArgumentException("--launcher " + launcher + " requires --instanceDir");
            }
            if (instanceDir != null) {
                profileGameDir = true;
            }
            return new Options(nows, minecraft, minecraftDir, manifest, launcher, instanceDir, offline, artifactDir, null, false, profileGameDir, gameDir);
        }

        Options withEmbeddedOfflinePayload() throws IOException {
            String resource = "/META-INF/nows/offline/install.properties";
            Properties embeddedManifest = loadEmbeddedProperties(resource);
            return new Options(
                    embeddedManifest.getProperty("nows.version", nowsVersion),
                    embeddedManifest.getProperty("minecraft.version", minecraftVersion),
                    minecraftDir,
                    manifest,
                    launcher,
                    instanceDir,
                    true,
                    artifactDir,
                    resource,
                    true,
                    profileGameDir,
                    gameDir
            );
        }

        boolean usesProfileGameDir() {
            return profileGameDir || gameDir != null || instanceDir != null;
        }

        boolean writesLauncherProfile() {
            return launcher.equals("official");
        }

        boolean writesMultiMcPack() {
            return launcher.equals("prism") || launcher.equals("multimc");
        }

        String manifestLocation() {
            if (embeddedManifestResource != null) {
                return embeddedManifestResource;
            }
            return manifest != null
                    ? manifest
                    : "https://files.nows.space/releases/nows/" + nowsVersion + "/" + minecraftVersion + "/install.properties";
        }

        Path artifactDir() {
            if (artifactDir != null) {
                return artifactDir;
            }
            if (manifest != null && isLocalLocation(manifest)) {
                Path manifestPath = localManifestPath(this, manifest);
                Path parent = manifestPath.getParent();
                if (parent != null) {
                    return parent;
                }
            }
            return Paths.get("").toAbsolutePath().normalize();
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) throw new IllegalArgumentException("Missing value for " + option);
            return args[index];
        }
    }
}
