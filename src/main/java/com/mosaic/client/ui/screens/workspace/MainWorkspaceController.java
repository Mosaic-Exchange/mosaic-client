package com.mosaic.client.ui.screens.workspace;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Controller for the Main Workspace screen.
 *
 * APP-MW-1 (#11): Layout structure          — DONE
 * APP-MW-3 (#13): Message input + send      — DONE (this class)
 *
 * Teammates: add your @FXML fields and logic in the section for your issue:
 *
 *   TODO APP-MW-2 (#12): Inject session list and wire New/Search Session buttons;
 *                         prepopulate chatHistory with hardcoded sample messages.
 *
 *   TODO APP-MW-4 (#14): Inject header labels and right-panel metadata labels;
 *                         bind to hardcoded active expert data.
 *
 *   TODO APP-MW-5 (#15): Wire Switch Expert → Navigator.showExpertSelection();
 *                         wire Clear Context (chatHistory.getChildren().clear());
 *                         wire End Session (confirmation dialog, then clear).
 */
public class MainWorkspaceController {

    // ── APP-MW-3 (#13) fields ────────────────────────────────
    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox       chatHistory;
    @FXML private TextArea   messageInput;

    // ── TODO APP-MW-2 (#12): add @FXML session list field here
    // ── TODO APP-MW-4 (#14): add @FXML header/metadata label fields here
    // ── TODO APP-MW-5 (#15): add @FXML control button fields here

    @FXML
    public void initialize() {
        // AC3: pressing Enter sends the message; Shift+Enter inserts a newline
        messageInput.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                onSend();
            }
        });
    }

    // ── AC3: Send button handler ─────────────────────────────
    @FXML
    private void onSend() {
        String text = messageInput.getText().trim();
        if (text.isEmpty()) return;

        appendUserMessage(text);  // AC3: append to chat window
        messageInput.clear();     // AC3: clear the input field
        scrollToBottom();         // AC4: scroll to latest message
    }

    // ── Helpers ──────────────────────────────────────────────

    private void appendUserMessage(String text) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_RIGHT);

        VBox bubble = new VBox();
        bubble.getStyleClass().add("message-bubble-user");

        Label label = new Label(text);
        label.setWrapText(true);
        bubble.getChildren().add(label);

        row.getChildren().add(bubble);
        chatHistory.getChildren().add(row);
    }

    private void scrollToBottom() {
        // Platform.runLater ensures the layout pass has completed before scrolling
        Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
    }
}
