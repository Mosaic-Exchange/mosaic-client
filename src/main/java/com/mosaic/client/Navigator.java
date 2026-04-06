package com.mosaic.client;

/**
 * Static navigation helper.
 * Any controller can call Navigator.showX() without holding a direct
 * reference to MainLayoutController.
 *
 * Initialised once in MosaicApp.start() after the main layout is loaded.
 *
 * TODO APP-2 (#6): Add slide/fade transition support when switching screens.
 */
public class Navigator {

    private static MainLayoutController mainLayout;

    // Currently selected expert state, shared between screens.
    private static String[] activeExpert;

    // Network state — set in MosaicApp before the UI appears.
    private static boolean        networkAvailable = false;
    private static NetworkManager networkManager;

    public static void init(MainLayoutController controller) {
        mainLayout = controller;
    }

    public static void showSplash()          { mainLayout.showSplash(); }
    public static void showWorkspace()       { mainLayout.showWorkspace(); }
    public static void showExpertSelection() { mainLayout.showExpertSelection(); }
    public static void showSettings()        { mainLayout.showSettings(); }

    /**
     * Store the selected expert so the workspace can pick it up.
     * @param name        expert display name
     * @param domain      expert domain
     * @param source      "Local" or "Remote"
     * @param adapterFile adapter filename
     * @param status      "Connected" or "Disconnected"
     */
    public static void setActiveExpert(String name, String domain, String source,
                                       String adapterFile, String status) {
        activeExpert = new String[]{ name, domain, source, adapterFile, status };
    }

    /** Returns the active expert array, or null if none has been selected yet. */
    public static String[] getActiveExpert() {
        return activeExpert == null ? null : activeExpert.clone();
    }

    // -------------------------------------------------------------------------
    // Network state
    // -------------------------------------------------------------------------

    /** Called from {@code MosaicApp.init()} with the result of the node startup. */
    public static void setNetworkAvailable(boolean available) { networkAvailable = available; }

    /** {@code true} if the Rumor node started successfully before the UI appeared. */
    public static boolean isNetworkAvailable() { return networkAvailable; }

    /** Called from {@code MosaicApp.start()} so any controller can subscribe to state changes. */
    public static void setNetworkManager(NetworkManager manager) { networkManager = manager; }

    /** Returns the shared {@link NetworkManager}, or {@code null} if not yet initialised. */
    public static NetworkManager getNetworkManager() { return networkManager; }
}
