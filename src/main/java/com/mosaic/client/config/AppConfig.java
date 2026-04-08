package com.mosaic.client.config;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public class AppConfig {
    private static final Path CONFIG_DIR  = Paths.get(System.getProperty("user.home"), ".mosaic");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("settings.properties");
    private static final String KEY_MY_IP     = "myIp";
    private static final String KEY_MY_PORT   = "myPort";
    private static final String KEY_SEED_IP   = "seedIp";
    private static final String KEY_SEED_PORT = "seedPort";

    public static final String DEFAULT_MY_IP     = "127.0.0.1";
    public static final String DEFAULT_MY_PORT   = "7010";
    public static final String DEFAULT_SEED_IP   = "127.0.0.1";
    public static final String DEFAULT_SEED_PORT = "7001";

    // Privacy
    private static final String KEY_SHARE_ANON = "shareAnonymousData";
    private static final String KEY_LOCAL_ONLY = "localOnlyMode";

    // Network mode
    private static final String KEY_NETWORK_MODE = "networkMode";
    public static final String DEFAULT_NETWORK_MODE = "Automatic";

    // Adapters
    private static final String KEY_BLUETOOTH = "bluetoothEnabled";
    private static final String KEY_WIFI      = "wifiEnabled";
    private static final String KEY_ETHERNET  = "ethernetEnabled";
    private static final String KEY_CUSTOM    = "customAdapters";

    private final Properties props = new Properties();

    // Load

    public void load() {
        props.setProperty(KEY_MY_IP,     DEFAULT_MY_IP);
        props.setProperty(KEY_MY_PORT,   DEFAULT_MY_PORT);
        props.setProperty(KEY_SEED_IP,   DEFAULT_SEED_IP);
        props.setProperty(KEY_SEED_PORT, DEFAULT_SEED_PORT);
        props.setProperty(KEY_SHARE_ANON,  "false");
        props.setProperty(KEY_LOCAL_ONLY,  "false");
        props.setProperty(KEY_NETWORK_MODE, DEFAULT_NETWORK_MODE);
        props.setProperty(KEY_BLUETOOTH,   "true");
        props.setProperty(KEY_WIFI,        "true");
        props.setProperty(KEY_ETHERNET,    "false");
        props.setProperty(KEY_CUSTOM,      "");


        if (!Files.exists(CONFIG_FILE)) return;

        try (InputStream in = Files.newInputStream(CONFIG_FILE)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("[AppConfig] Could not read settings file: " + e.getMessage());
        }
    }

    // Save

    public void save(
        String myIp, String myPort, String seedIp, String seedPort,
        boolean shareAnon, boolean localOnly,
        String networkMode,
        boolean bluetooth, boolean wifi, boolean ethernet,
        String customAdapter
    ) throws IOException {
        props.setProperty(KEY_MY_IP,        myIp);
        props.setProperty(KEY_MY_PORT,      myPort);
        props.setProperty(KEY_SEED_IP,      seedIp);
        props.setProperty(KEY_SEED_PORT,    seedPort);
        props.setProperty(KEY_SHARE_ANON,   String.valueOf(shareAnon));
        props.setProperty(KEY_LOCAL_ONLY,   String.valueOf(localOnly));
        props.setProperty(KEY_NETWORK_MODE, networkMode);
        props.setProperty(KEY_BLUETOOTH,    String.valueOf(bluetooth));
        props.setProperty(KEY_WIFI,         String.valueOf(wifi));
        props.setProperty(KEY_ETHERNET,     String.valueOf(ethernet));
        props.setProperty(KEY_CUSTOM,       customAdapter);

        Files.createDirectories(CONFIG_DIR);

        try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
            props.store(out, "Mosaic Settings");
        }
    }

    // ── Getters ─────────────────────────────────────────────

    public String  getMyIp()       { return props.getProperty(KEY_MY_IP,        DEFAULT_MY_IP); }
    public String  getMyPort()     { return props.getProperty(KEY_MY_PORT,      DEFAULT_MY_PORT); }
    public String  getSeedIp()     { return props.getProperty(KEY_SEED_IP,      DEFAULT_SEED_IP); }
    public String  getSeedPort()   { return props.getProperty(KEY_SEED_PORT,    DEFAULT_SEED_PORT); }
    public boolean getShareAnon()  { return Boolean.parseBoolean(props.getProperty(KEY_SHARE_ANON,  "false")); }
    public boolean getLocalOnly()  { return Boolean.parseBoolean(props.getProperty(KEY_LOCAL_ONLY,  "false")); }
    public String  getNetworkMode(){ return props.getProperty(KEY_NETWORK_MODE, DEFAULT_NETWORK_MODE); }
    public boolean getBluetooth()  { return Boolean.parseBoolean(props.getProperty(KEY_BLUETOOTH,   "true")); }
    public boolean getWifi()       { return Boolean.parseBoolean(props.getProperty(KEY_WIFI,        "true")); }
    public boolean getEthernet()   { return Boolean.parseBoolean(props.getProperty(KEY_ETHERNET,    "false")); }
    public String  getCustom()     { return props.getProperty(KEY_CUSTOM, ""); }

    public int getMyPortAsInt() {
        try { return Integer.parseInt(getMyPort()); }
        catch (NumberFormatException e) { return Integer.parseInt(DEFAULT_MY_PORT); }
    }

    public int getSeedPortAsInt() {
        try { return Integer.parseInt(getSeedPort()); }
        catch (NumberFormatException e) { return Integer.parseInt(DEFAULT_SEED_PORT); }
    }

}