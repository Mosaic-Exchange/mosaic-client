package com.mosaic.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * Application configuration loaded from a YAML-style file ({@code mosaic.yml})
 * next to the runnable jar (or the project root when running from the IDE).
 *
 * <p>Format is simple key-value pairs ({@code key: value}), one per line.
 * Lines starting with {@code #} are comments. Blank lines are ignored.
 */

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class AppConfig {
    @JsonIgnore
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
     * Writes the current config back to the active config file (mosaic.yml).
     * Call this whenever the user saves settings.
     */
    public void save() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.writeValue(configPath().toFile(), this);
        System.out.println("[config] Saved settings to " + configPath());
    }

    public void setPort(int port)                 { this.port = port; }
    public void setLlmServerPort(int port)        { this.llmServerPort = port; }
    public void setNodeType(String nodeType)      { this.nodeType = nodeType; }
    public void setSeed(String seed)              { this.seed = seed; }
    public void setDebugEnabled(boolean enabled)  { this.debugEnabled = enabled; }
    public void setDataDir(String dataDir)        { this.dataDir = dataDir; }

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

        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            config = mapper.readValue(path.toFile(), AppConfig.class);
            System.out.println("[config] Loaded " + filename + " — port=" + config.port
                + " llm-port=" + config.llmServerPort
                + " type=" + config.nodeType
                + " seed=" + (config.seed.isEmpty() ? "(none)" : config.seed)
                + " debug=" + config.debugEnabled
                + " data-dir=" + config.dataDir);
        } catch (IOException e) {
            System.err.println("[config] Failed to read " + filename + ": " + e.getMessage());
        }

        return config;
    }

    /**
     * Loads config from {@link #configPath()}.
     * Returns defaults if the file is missing or unreadable.
     */
    public static AppConfig load() {
        return load(getActiveConfigFilename());
    }

    /**
     * Writes a default config file if none exists. Called once at startup
     * so the user always has a reference to edit.
     * @param filename Configuration file name (e.g. {@code mosaic.yml})
     */
    public static void writeDefaultIfMissing(String filename) {
        Path path = configPath(filename);
        if (Files.exists(path)) return;

                String content = """
                # Mosaic configuration
                # Lives next to the runnable jar (or project root in dev).

                # Network port this node listens on
                port: 7000

                # Network port for the local LLM server (middleware)
                llm-server-port: 4000

                # Node type: basic | seed | eviction | master
                node-type: basic

                # Seed node to bootstrap into the cluster (host:port)
                # Leave empty to start as a standalone node.
                seed:

                # Debug snapshot file (written periodically while running)
                debug-file: mosaic-debug.txt

                # Set to true to enable periodic debug snapshots
                debug-enabled: false

                # Directory used to store the database and adapters (default: ~/.mosaic)
                # data-dir: /path/to/custom/dir
                """;
        try {
            Files.writeString(path, content);
            System.out.println("[config] Created default " + filename);
        } catch (IOException e) {
            // Non-fatal — the app works fine without the file
            System.err.println("[config] Could not write default " + filename + ": " + e.getMessage());
        }
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
