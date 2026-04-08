package com.mosaic.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Application configuration loaded from a YAML-style file ({@code mosaic.yml})
 * next to the runnable jar (or the project root when running from the IDE).
 *
 * <p>Format is simple key-value pairs ({@code key: value}), one per line.
 * Lines starting with {@code #} are comments. Blank lines are ignored.
 */
public class AppConfig {

    private int port = 7000;
    private String nodeType = "basic";
    private String seed = "";
    private String debugFile = "mosaic-debug.txt";
    private boolean debugEnabled = false;

    private AppConfig() {}

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

    /** {@code mosaic.yml} under {@link #appHome()}. */
    public static Path configPath() {
        return appHome().resolve("mosaic.yml");
    }

    /** Non-empty seed entries parsed from {@link #seed()} (single value for now). */
    public String[] seedAddresses() {
        if (seed == null || seed.isBlank()) return new String[0];
        return new String[] { seed.trim() };
    }

    /**
     * Loads config from {@link #configPath()}.
     * Returns defaults if the file is missing or unreadable.
     */
    public static AppConfig load() {
        Path path = configPath();
        AppConfig config = new AppConfig();

        if (!Files.isRegularFile(path)) {
            System.out.println("[config] mosaic.yml not found — using defaults");
            return config;
        }

        try {
            List<String> lines = Files.readAllLines(path);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                int colon = line.indexOf(':');
                if (colon < 0) continue;

                String key = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();

                switch (key) {
                    case "port"          -> config.port = parsePort(value);
                    case "node-type"     -> config.nodeType = value;
                    case "seed"          -> config.seed = value;
                    case "debug-file"    -> config.debugFile = value;
                    case "debug-enabled" -> config.debugEnabled = parseBoolean(value);
                }
            }
            System.out.println("[config] Loaded mosaic.yml — port=" + config.port
                    + " type=" + config.nodeType
                    + " seed=" + (config.seed.isEmpty() ? "(none)" : config.seed)
                    + " debug=" + config.debugEnabled);
        } catch (IOException e) {
            System.err.println("[config] Failed to read mosaic.yml: " + e.getMessage());
        }

        return config;
    }

    /**
     * Writes a default config file if none exists. Called once at startup
     * so the user always has a reference to edit.
     */
    public static void writeDefaultIfMissing() {
        Path path = configPath();
        if (Files.exists(path)) return;

                String content = """
                # Mosaic configuration
                # Lives next to the runnable jar (or project root in dev).

                # Network port this node listens on
                port: 7000

                # Node type: basic | seed | eviction | master
                node-type: basic

                # Seed node to bootstrap into the cluster (host:port)
                # Leave empty to start as a standalone node.
                seed:

                # Debug snapshot file (written periodically while running)
                debug-file: mosaic-debug.txt

                # Set to true to enable periodic debug snapshots
                debug-enabled: false
                """;
        try {
            Files.writeString(path, content);
            System.out.println("[config] Created default mosaic.yml");
        } catch (IOException e) {
            // Non-fatal — the app works fine without the file
            System.err.println("[config] Could not write default mosaic.yml: " + e.getMessage());
        }
    }

    // -- Accessors --

    public int port()           { return port; }
    public String nodeType()    { return nodeType; }
    public String seed()        { return seed; }
    public String debugFile()   { return debugFile; }
    public boolean debugEnabled() { return debugEnabled; }

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
