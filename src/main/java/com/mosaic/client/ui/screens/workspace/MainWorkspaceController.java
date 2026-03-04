package com.mosaic.client.ui.screens.workspace;

import javafx.fxml.FXML;

/**
 * Controller for the Main Workspace screen.
 *
 * APP-MW-1 (#11): Layout structure — DONE.
 *   Four panels are in place (top header, left sidebar, center chat, right expert panel).
 *   All panels currently show placeholder labels for teammates to fill in.
 *
 * Teammates: add your @FXML fields and logic in the section for your issue:
 *
 *   TODO APP-MW-2 (#12): Inject session list view and chat history VBox;
 *                         wire New Session, Search Session, session click highlight.
 *
 *   TODO APP-MW-3 (#13): Inject message TextArea and Send button;
 *                         wire Send action and Enter key; auto-scroll chat on send.
 *
 *   TODO APP-MW-4 (#14): Inject header labels and right-panel metadata labels;
 *                         bind to hardcoded active expert data.
 *
 *   TODO APP-MW-5 (#15): Wire Switch Expert → Navigator.showExpertSelection();
 *                         wire Clear Context (clear chat UI only);
 *                         wire End Session (confirmation dialog, reset chat view).
 */
public class MainWorkspaceController {

    @FXML
    public void initialize() {
        // Layout initialised by FXML — no code needed here for #11.
        // Teammates: add your initialisation logic below.
    }
}
