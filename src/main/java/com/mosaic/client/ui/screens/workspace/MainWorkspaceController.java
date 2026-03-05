package com.mosaic.client.ui.screens.workspace;

import javafx.fxml.FXML;

/**
 * Controller for the Main Workspace screen.
 * Implementation is spread across sub-issues #11–15. See TODOs below.
 *
 * TODO APP-MW-1 (#11) — Layout structure
 *   AC1: Screen reachable via Navigator.showWorkspace()
 *   AC2: BorderPane with left sidebar, central chat panel, right expert panel, top header
 *   AC3: All panels resize proportionally with window
 *   AC4: No backend calls needed to render
 *
 * TODO APP-MW-2 (#12) — Session list + chat history
 *   AC1: Left sidebar: "New Session" button, "Search Session" button, hardcoded session list
 *   AC2: Clicking a session highlights it visually
 *   AC3: Central area is a ScrollPane; shows hardcoded User + Expert message bubbles
 *
 * TODO APP-MW-3 (#13) — Message input + Send
 *   AC1: TextField at the bottom of the chat panel
 *   AC2: Send button beside the field
 *   AC3: Enter or Send click appends message locally and clears the field
 *   AC4: ScrollPane auto-scrolls to latest message
 *
 * TODO APP-MW-4 (#14) — Expert header + metadata panel
 *   AC1: Top header shows Expert name, Source (Local/Network), Mode (Private/Public) — hardcoded
 *   AC2: Right panel shows Domain, Status, Adapter name, File hash — hardcoded
 *
 * TODO APP-MW-5 (#15) — Workspace controls
 *   AC1: "Switch Expert" button → Navigator.showExpertSelection()
 *   AC2: "Clear Context" button → clears chat panel (UI only)
 *   AC3: "End Session" button → confirmation dialog, then reset chat view
 */
public class MainWorkspaceController {

    @FXML
    public void initialize() {
        // TODO APP-MW-1 (#11): Initialise layout panels and size bindings
    }
}
