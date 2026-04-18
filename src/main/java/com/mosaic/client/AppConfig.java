package com.mosaic.client;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;

/**
 * Application configuration loaded from a YAML-style file ({@code mosaic.yml})
 * next to the runnable jar (or the project root when running from the IDE).
 *
 * <p>Format is simple key-value pairs ({@code key: value}), one per line.
 * Lines starting with {@code #} are comments. Blank lines are ignored.
 */
public class AppConfig {

    private static String activeConfigFilename;

    private int port = 7000;
    private int llmServerPort = 4000;
    private String nodeType = "basic";
    private String seed = "";
    private String debugFile = "mosaic-debug.txt";
    private boolean debugEnabled = false;
    private String dataDir = System.getProperty("user.home") + "/.mosaic";

    private AppConfig() {}

    /**
     * Sets the configuration file name to use globally (e.g. {@code mosaic.yml}).
     */
    public static void setActiveConfigFilename(String filename) {
        if (filename != null && !filename.isBlank()) {
            activeConfigFilename = filename;
        }
    }

    /**
     * @return The currently active configuration file name.
     */
    public static String getActiveConfigFilename() {
        return activeConfigFilename != null ? activeConfigFilename : "mosaic.yml";
    }

    /**
     * Base directory for config and generated defaults: the folder containing
     * the runnable {@code .jar}, or (when running from compiled classes) the
     * Maven/Gradle module root, otherwise the current working directory.
     */
    public static Path appHome() {
        try {
            URI uri = AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path p = Path.of(uri);
            if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                Path parent = p.getParent();
                if (parent != null) return parent;
            } else if (Files.isDirectory(p)) {
                Path name = p.getFileName();
                if (name != null && "classes".equalsIgnoreCase(name.toString())) {
                    Path moduleRoot = p.getParent();
                    if (moduleRoot != null && "target".equalsIgnoreCase(
                            moduleRoot.getFileName() != null ? moduleRoot.getFileName().toString() : "")) {
                        Path project = moduleRoot.getParent();
                        if (project != null) return project;
                    }
                }
            }
        } catch (URISyntaxException | NullPointerException ignored) {
            // Fall through
        }
        return Path.of("").toAbsolutePath();
    }

    /**
     * @param filename Configuration file name (e.g. {@code mosaic.yml})
     * @return Path to the config file under {@link #appHome()}.
     */
    public static Path configPath(String filename) {
        return appHome().resolve(filename);
    }

    /** {@link #getActiveConfigFilename()} under {@link #appHome()}. */
    public static Path configPath() {
        return configPath(getActiveConfigFilename());
    }

    /** Non-empty seed entries parsed from {@link #seed()} (single value for now). */
    public String[] seedAddresses() {
        if (seed == null || seed.isBlank()) return new String[0];
        return new String[] { seed.trim() };
    }

    /**
     * Loads config from a specific file name under {@link #appHome()}.
     * Returns defaults if the file is missing or unreadable.
     */
    public static AppConfig load(String filename) {
        Path path = configPath(filename);
        AppConfig config = new AppConfig();

        if (!Files.isRegularFile(path)) {
            System.out.println("[config] " + filename + " not found — using defaults");
            return config;
        }

        try (InputStream inputStream = Files.newInputStream(path)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(inputStream);
            if (data != null) {
                if (data.containsKey("port")) config.port = toInt(data.get("port"), config.port);
                if (data.containsKey("llm-server-port")) config.llmServerPort = toInt(data.get("llm-server-port"), config.llmServerPort);
                if (data.containsKey("node-type")) config.nodeType = String.valueOf(data.get("node-type"));
                if (data.containsKey("seed")) config.seed = data.get("seed") != null ? String.valueOf(data.get("seed")) : "";
                if (data.containsKey("debug-file")) config.debugFile = String.valueOf(data.get("debug-file"));
                if (data.containsKey("debug-enabled")) config.debugEnabled = Boolean.parseBoolean(String.valueOf(data.get("debug-enabled")));
                if (data.containsKey("data-dir")) config.dataDir = String.valueOf(data.get("data-dir"));
            }
            System.out.println("[config] Loaded " + filename + " — port=" + config.port
                    + " llm-port=" + config.llmServerPort
                    + " type=" + config.nodeType
                    + " seed=" + (config.seed != null && !config.seed.isEmpty() ? config.seed : "(none)")
                    + " debug=" + config.debugEnabled
                    + " data-dir=" + config.dataDir);
        } catch (Exception e) {
            System.err.println("[config] Failed to read " + filename + ": " + e.getMessage());
        }

        return config;
    }

    private static int toInt(Object obj, int defaultValue) {
        if (obj instanceof Number) return ((Number) obj).intValue();
        try {
            return Integer.parseInt(String.valueOf(obj));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Loads config from {@link #configPath()}.
     * Returns defaults if the file is missing or unreadable.
     */
    public static AppConfig load() {
        return load(getActiveConfigFilename());
    }

    /**
     * Writes the current configuration to a file under {@link #appHome()}.
     * @param filename Configuration file name (e.g. {@code mosaic.yml})
     */
    public void save(String filename) {
        Path path = configPath(filename);
        Map<String, Object> data = new HashMap<>();
        data.put("port", port);
        data.put("llm-server-port", llmServerPort);
        data.put("node-type", nodeType);
        data.put("seed", seed);
        data.put("debug-file", debugFile);
        data.put("debug-enabled", debugEnabled);
        data.put("data-dir", dataDir);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);

        try (OutputStream outputStream = Files.newOutputStream(path)) {
            yaml.dump(data, new java.io.OutputStreamWriter(outputStream));
            System.out.println("[config] Saved " + filename);
        } catch (Exception e) {
            System.err.println("[config] Could not save " + filename + ": " + e.getMessage());
        }
    }

    /**
     * Writes the current configuration to the active configuration file.
     */
    public void save() {
        save(getActiveConfigFilename());
    }

    /**
     * Writes a default config file if none exists. Called once at startup
     * so the user always has a reference to edit.
     * @param filename Configuration file name (e.g. {@code mosaic.yml})
     */
    public static void writeDefaultIfMissing(String filename) {
        Path path = configPath(filename);
        if (Files.exists(path)) return;

        AppConfig config = new AppConfig();
        config.save(filename);
        System.out.println("[config] Created default " + filename);
    }

    /**
     * Writes a default config file if none exists. Called once at startup
     * so the user always has a reference to edit.
     */
    public static void writeDefaultIfMissing() {
        writeDefaultIfMissing(getActiveConfigFilename());
    }

    // -- Accessors --

    public int port()             { return port; }
    public int llmServerPort()    { return llmServerPort; }
    public String nodeType()      { return nodeType; }
    public String seed()          { return seed; }
    public String debugFile()     { return debugFile; }
    public boolean debugEnabled() { return debugEnabled; }
    public Path dataDir()         { return Path.of(dataDir); }
    public Path logDir()          { return dataDir().resolve("logs"); }

    // -- Parse helpers --

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s);
            if (p > 0 && p <= 65535) return p;
        } catch (NumberFormatException ignored) {}
        System.err.println("[config] Invalid port '" + s + "', using 7000");
        return 7000;
    }

    private static boolean parseBoolean(String s) {
        return "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "1".equals(s);
    }
}
