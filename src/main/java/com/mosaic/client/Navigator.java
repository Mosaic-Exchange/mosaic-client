package com.mosaic.client;

import com.mosaic.client.ui.screens.expert.Expert;

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
    private static Expert activeExpert;

    public static void init(MainLayoutController controller) {
        mainLayout = controller;
    }

    public static void showSplash()          { mainLayout.showSplash(); }
    public static void showWorkspace()       { mainLayout.showWorkspace(); }
    public static void showExpertSelection() { mainLayout.showExpertSelection(); }
    public static void showSettings()        { mainLayout.showSettings(); }

    /**
     * Store the selected expert so the workspace can pick it up.
     * @param expert the expert to set as active
     */
    public static void setActiveExpert(Expert expert) {
        activeExpert = expert;
    }

    /** Returns the active expert, or null if none has been selected yet. */
    public static Expert getActiveExpert() {
        return activeExpert;
    }
}
