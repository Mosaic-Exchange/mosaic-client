package com.mosaic.client.ui.screens.workspace;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.mosaic.client.Navigator;

/**
 * Controller for the Main Workspace screen.
 *
 * APP-MW-1 (#11): Layout structure          — DONE
 * APP-MW-3 (#13): Message input + send      — DONE
 * APP-MW-4 (#14): Expert metadata panel     — DONE (this class)
 * APP-MW-5 (#15): Workspace controls        — DONE (this class)
 *
 * Teammates: add your @FXML fields and logic in the section for your issue:
 *
 *   TODO APP-MW-2 (#12): Inject session list and wire New/Search Session buttons;
 *                         prepopulate chatHistory with hardcoded sample messages.
 *
 *   APP-MW-4 (#14): Header labels and right-panel metadata labels              — DONE
 *   APP-MW-5 (#15): Wire Switch Expert → Navigator.showExpertSelection();      — DONE
 *                    wire Clear Context (chatHistory.getChildren().clear());    — DONE
 *                    wire End Session (confirmation dialog, then clear).        — DONE
 */
public class MainWorkspaceController {

    // ── APP-MW-3 (#13) fields ────────────────────────────────
    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox       chatHistory;
    @FXML private TextArea   messageInput;

    // ── TODO APP-MW-2 (#12): add @FXML session list field here

    // ── APP-MW-4 (#14) fields ────────────────────────────────
    @FXML private Label headerExpertName;
    @FXML private Label headerExpertSource;
    @FXML private Label headerExpertMode;
    @FXML private Label metaExpertName;
    @FXML private Label metaExpertDomain;
    @FXML private Label metaExpertSource;
    @FXML private Label metaAdapterFile;
    @FXML private Label metaExpertStatus;

    // ── APP-MW-5 (#15) fields ────────────────────────────────
    @FXML private Button switchExpertBtn;
    @FXML private Button clearContextBtn;
    @FXML private Button endSessionBtn;

    @FXML
    public void initialize() {
        // AC3: Enter sends; Shift+Enter inserts a newline.
        // Both cases consume the event so JavaFX does not also insert a newline on send.
        messageInput.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                if (event.isShiftDown()) {
                    messageInput.insertText(messageInput.getCaretPosition(), "\n");
                } else {
                    onSend();
                }
            }
        });

        // AC4: scroll to bottom whenever the chat history grows.
        // Height listener fires after layout is measured — more reliable than Platform.runLater.
        chatHistory.heightProperty().addListener(
                (obs, oldHeight, newHeight) -> chatScrollPane.setVvalue(1.0));

        // APP-MW-4 (#14): Populate with hardcoded expert data.
        // Will be replaced with real adapter data when AI integration lands.
        loadActiveExpert("Gardening Expert", "Gardening", "Local",
                         "gardening_expert.gguf", "Connected");
    }

    // ── APP-MW-4 (#14): Expert metadata helpers ──────────────

    private void loadActiveExpert(String name, String domain, String source,
                                  String adapterFile, String status) {
        headerExpertName.setText(name);
        headerExpertSource.setText(source);
        headerExpertMode.setText("Inference");
        metaExpertName.setText(name);
        metaExpertDomain.setText(domain);
        metaExpertSource.setText(source);
        metaAdapterFile.setText(adapterFile);
        metaExpertStatus.setText(status);
    }

    // ── AC3: Send button handler ─────────────────────────────
    @FXML
    private void onSend() {
        String text = messageInput.getText().trim();
        if (text.isEmpty()) return;

        appendUserMessage(text); // AC3: append to chat window
        messageInput.clear();    // AC3: clear the input field
    }

    // ── APP-MW-5 (#15): Workspace control handlers ───────────

    @FXML
    private void onSwitchExpert() {
        Navigator.showExpertSelection();
    }

    @FXML
    private void onClearContext() {
        chatHistory.getChildren().clear();
    }

    @FXML
    private void onEndSession() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "End this session? All messages will be cleared.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("End Session");
        confirm.setHeaderText(null);
        confirm.showAndWait()
               .filter(btn -> btn == ButtonType.OK)
               .ifPresent(btn -> {
                   chatHistory.getChildren().clear();
                   messageInput.clear();
               });
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
}
