package space.nows.mcnows.installer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
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

        listener.log("[NowsInstaller] minecraft dir: " + options.minecraftDir);
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
        installLauncherProfile(options, profile);
        listener.log("[NowsInstaller] launcher profiles: " + options.minecraftDir.resolve("launcher_profiles.json"));
        listener.log("[NowsInstaller] default game dir: " + options.minecraftDir);
        listener.log("[NowsInstaller] optional profile game dir: " + profileGameDirectory(options));
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

        Path destination = options.minecraftDir.resolve("libraries").resolve(relativePath);
        Files.createDirectories(destination.getParent());

        if (Files.isRegularFile(destination) && !expectedSha256.isEmpty()
                && sha256(destination).equalsIgnoreCase(expectedSha256)) {
            return new InstalledLibrary(coordinate, relativePath, sourceUrl, destination);
        }

        if (source.equals("embedded")) {
            copyEmbedded(required(manifest, prefix + "resource"), destination);
        } else if (options.embeddedArtifacts) {
            copyEmbedded("/META-INF/nows/offline-libraries/" + relativePath, destination);
        } else if (options.offline) {
            copyLocalArtifact(options, manifest, prefix, relativePath, destination);
        } else if (source.equals("internet")) {
            downloadInternetArtifact(manifest, prefix, relativePath, coordinate, sourceUrl, destination);
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

        return new InstalledLibrary(coordinate, relativePath, options.offline || options.embeddedArtifacts ? "" : sourceUrl, destination);
    }

    private static void downloadInternetArtifact(
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
                download(URI.create(sourceUrl), destination);
                return;
            } catch (IOException primaryFailure) {
                if (isBlank(fallbackUrl) || fallbackUrl.equals(sourceUrl)) {
                    throw primaryFailure;
                }
                download(URI.create(fallbackUrl), destination);
                return;
            }
        }
        if (isBlank(fallbackUrl)) {
            throw new IllegalArgumentException("Missing URL for internet artifact: " + coordinate);
        }
        download(URI.create(fallbackUrl), destination);
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
            download(URI.create(sourceUrl), destination);
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
    ) throws IOException {
        String profile = profileId(options);
        Instant now = Instant.now();
        Path versionDir = options.minecraftDir.resolve("versions").resolve(profile);
        Files.createDirectories(versionDir);
        boolean copiedInheritedJar = copyInheritedVersionJar(options, versionDir.resolve(profile + ".jar"));
        StringBuilder json = new StringBuilder();

        json.append("{\n");
        json.append("  \"id\": ").append(quote(profile)).append(",\n");
        json.append("  \"inheritsFrom\": ").append(quote(options.minecraftVersion)).append(",\n");
        if (!copiedInheritedJar) {
            json.append("  \"jar\": ").append(quote(options.minecraftVersion)).append(",\n");
        }
        json.append("  \"mainClass\": ")
                .append(quote(manifest.getProperty("mainClass", "space.nows.mcnows.runtime.NowsLauncher")))
                .append(",\n");
        json.append("  \"type\": \"release\",\n");
        json.append("  \"time\": ").append(quote(now.toString())).append(",\n");
        json.append("  \"releaseTime\": ").append(quote(now.toString())).append(",\n");
        json.append("  \"libraries\": [\n");

        for (int i = 0; i < libraries.size(); i++) {
            InstalledLibrary lib = libraries.get(i);
            json.append("    {\"name\":").append(quote(lib.coordinate())).append(",\"downloads\":{\"artifact\":{");
            json.append("\"path\":").append(quote(lib.relativePath())).append(',');
            json.append("\"sha1\":").append(quote(sha1(lib.file()))).append(',');
            json.append("\"size\":").append(Files.size(lib.file()));
            if (!isBlank(lib.sourceUrl())) {
                json.append(",\"url\":").append(quote(lib.sourceUrl()));
            }
            json.append("}}}");
            if (i + 1 < libraries.size()) json.append(',');
            json.append('\n');
        }

        json.append("  ],\n");
        json.append("  \"arguments\": {\n");
        json.append("    \"game\": [\"--nowsMinecraftVersion\", ")
                .append(quote(options.minecraftVersion)).append("],\n");
        json.append("    \"jvm\": [");
        json.append(quote("-Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector"));
        json.append(", ").append(quote("-Dnows.domain=nows.space"));
        json.append("]\n");
        json.append("  }\n");
        json.append("}\n");

        writeString(versionDir.resolve(profile + ".json"), json.toString());
        return profile;
    }

    private static boolean copyInheritedVersionJar(Options options, Path target) throws IOException {
        Path source = options.minecraftDir
                .resolve("versions")
                .resolve(options.minecraftVersion)
                .resolve(options.minecraftVersion + ".jar");
        if (!Files.isRegularFile(source)) {
            return false;
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return true;
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
        if (options.profileGameDir) {
            profile.put("gameDir", gameDir.toString());
        }
        String icon = launcherIcon();
        if (icon != null) {
            profile.put("icon", icon);
        }
        return profile;
    }

    private static Path profileGameDirectory(Options options) {
        return options.minecraftDir.resolve("nows").resolve("profiles").resolve(profileId(options));
    }

    private static Path minecraftModsDirectory(Options options) {
        return options.minecraftDir.resolve("mods");
    }

    private static Path profileModsDirectory(Options options) {
        return profileGameDirectory(options).resolve("mods");
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
        return downloadProperties(URI.create(location));
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

    private static HttpURLConnection openConnection(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();
        if (status / 100 != 2) {
            connection.disconnect();
            throw new IOException("HTTP " + status + " for " + uri);
        }
        return connection;
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

    static Path defaultMinecraftDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (!isBlank(appData)) {
                return Paths.get(appData, ".minecraft").toAbsolutePath().normalize();
            }
            return Paths.get(System.getProperty("user.home"), "AppData", "Roaming", ".minecraft")
                    .toAbsolutePath().normalize();
        }
        if (os.contains("mac")) {
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support", "minecraft")
                    .toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".minecraft").toAbsolutePath().normalize();
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
    }

    static final class Options {
        final String nowsVersion;
        final String minecraftVersion;
        final Path minecraftDir;
        final String manifest;
        final boolean offline;
        final Path artifactDir;
        final String embeddedManifestResource;
        final boolean embeddedArtifacts;
        final boolean profileGameDir;

        private Options(
                String nowsVersion,
                String minecraftVersion,
                Path minecraftDir,
                String manifest,
                boolean offline,
                Path artifactDir,
                String embeddedManifestResource,
                boolean embeddedArtifacts,
                boolean profileGameDir
        ) {
            this.nowsVersion = nowsVersion;
            this.minecraftVersion = minecraftVersion;
            this.minecraftDir = minecraftDir;
            this.manifest = manifest;
            this.offline = offline;
            this.artifactDir = artifactDir;
            this.embeddedManifestResource = embeddedManifestResource;
            this.embeddedArtifacts = embeddedArtifacts;
            this.profileGameDir = profileGameDir;
        }

        static Options parse(String[] args) {
            String nows = defaultNowsVersion();
            String minecraft = defaultMinecraftVersion();
            String manifest = null;
            boolean offline = false;
            boolean profileGameDir = false;
            Path artifactDir = null;
            Path minecraftDir = defaultMinecraftDir();

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
                } else if ("--offline".equals(arg)) {
                    offline = true;
                } else if ("--profileGameDir".equals(arg)) {
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
            return new Options(nows, minecraft, minecraftDir, manifest, offline, artifactDir, null, false, profileGameDir);
        }

        Options withEmbeddedOfflinePayload() throws IOException {
            String resource = "/META-INF/nows/offline/install.properties";
            Properties embeddedManifest = loadEmbeddedProperties(resource);
            return new Options(
                    embeddedManifest.getProperty("nows.version", nowsVersion),
                    embeddedManifest.getProperty("minecraft.version", minecraftVersion),
                    minecraftDir,
                    manifest,
                    false,
                    artifactDir,
                    resource,
                    true,
                    profileGameDir
            );
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
