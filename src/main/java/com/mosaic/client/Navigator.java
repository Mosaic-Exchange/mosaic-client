package com.mosaic.client;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import com.mosaic.client.db.model.ChatSession;
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
    private static final ObjectProperty<Expert> activeExpert = new SimpleObjectProperty<>(Expert.BASE_MODEL);

    // Currently selected chat session, shared between screens.
    private static final ObjectProperty<ChatSession> activeSession = new SimpleObjectProperty<>();

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
        activeExpert.set(expert);
    }

    /** Returns the active expert, or null if none has been selected yet. */
    public static Expert getActiveExpert() {
        return activeExpert.get();
    }

    public static ReadOnlyObjectProperty<Expert> activeExpertProperty() {
        return activeExpert;
    }

    /**
     * Store the active chat session so the workspace can pick it up.
     * @param session the chat session to set as active
     */
    public static void setActiveSession(ChatSession session) {
        activeSession.set(session);
    }

    /** Returns the active session, or null if none is selected. */
    public static ChatSession getActiveSession() {
        return activeSession.get();
    }

    public static ReadOnlyObjectProperty<ChatSession> activeSessionProperty() {
        return activeSession;
    }
}
