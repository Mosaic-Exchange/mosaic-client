package com.mosaic.client.ui.screens.workspace;

import com.mosaic.client.ConnectionMonitor;
import com.mosaic.client.InferenceSession;
import com.mosaic.client.Navigator;
import com.mosaic.client.RumorClient;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Controller for the Main Workspace screen.
 *
 * APP-MW-1 (#11): Layout structure          — DONE
 * APP-MW-3 (#13): Message input + send      — DONE
 * APP-MW-4 (#14): Expert metadata panel     — DONE
 * APP-MW-5 (#15): Workspace controls        — DONE
 * Network integration (BLOCK 8):            — DONE (inference wiring, stop, live status)
 */
public class MainWorkspaceController {

    // ── MW-2 fields ──────────────────────────────────────────
    @FXML private ListView<String> sessionListView;

    // ── APP-MW-3 (#13) fields ────────────────────────────────
    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox       chatHistory;
    @FXML private TextArea   messageInput;
    @FXML private Button     sendBtn;
    @FXML private Button     stopBtn;

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
    @FXML private Button clearContextBtn;

    // ── Inference state ──────────────────────────────────────
    private InferenceSession currentSession;
    /** The label inside the in-progress expert bubble, appended to on each token. */
    private Label            streamingLabel;
    /** The typing-indicator row added to chatHistory while WAITING. */
    private HBox             typingRow;
    private Timeline         typingTimeline;

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    @FXML
    public void initialize() {
        // Stop button takes no space when hidden.
        stopBtn.managedProperty().bind(stopBtn.visibleProperty());

        // Enter sends; Shift+Enter inserts a newline.
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

        // Scroll to bottom whenever chat history grows.
        chatHistory.heightProperty().addListener(
            (obs, oldH, newH) -> chatScrollPane.setVvalue(1.0));

        sessionListView.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldVal, newVal) -> { if (newVal != null) loadChat(newVal); });

        // Load expert metadata.
        String[] expert = Navigator.getActiveExpert();
        if (expert != null) {
            loadActiveExpert(expert[0], expert[1], expert[2], expert[3], expert[4]);
        } else {
            loadActiveExpert("Gardening Expert", "Gardening", "Local",
                             "gardening_expert.gguf", "Connected");
        }

        // Subscribe to live peer status changes for the active expert.
        ConnectionMonitor mon = Navigator.getConnectionMonitor();
        if (mon != null) {
            mon.onPeerStatusChanged((nodeId, status) -> {
                String[] exp = Navigator.getActiveExpert();
                // Only update the panel for Remote experts (Local inference is always available).
                if (exp != null && "Remote".equalsIgnoreCase(exp[2])) {
                    boolean alive = "ALIVE".equalsIgnoreCase(status);
                    String displayStatus = alive ? "Connected" : "Disconnected";
                    String cssClass      = alive ? "expert-status-connected" : "expert-status-disconnected";
                    // Callback is already dispatched via Platform.runLater() by ConnectionMonitor.
                    metaExpertStatus.setText(displayStatus);
                    metaExpertStatus.getStyleClass()
                        .removeAll("expert-status-connected", "expert-status-disconnected");
                    metaExpertStatus.getStyleClass().add(cssClass);
                }
            });
        }
    }

    // -------------------------------------------------------------------------
    // Expert metadata
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Send / Stop
    // -------------------------------------------------------------------------

    @FXML
    private void onSend() {
        String text = messageInput.getText().trim();
        if (text.isEmpty()) return;

        appendUserMessage(text);
        messageInput.clear();

        String[] expert  = Navigator.getActiveExpert();
        boolean  isLocal = expert == null || "Local".equalsIgnoreCase(expert[2]);
        String   model   = expert != null ? expert[3] : null;

        RumorClient client = Navigator.getRumorClient();
        if (client == null) {
            appendErrorBubble("Network client not initialised — cannot send request.");
            return;
        }

        currentSession = new InferenceSession(client)
            .onWaiting(this::onSessionWaiting)
            .onToken(this::onSessionToken)
            .onDone(this::onSessionDone)
            .onError(this::onSessionError)
            .onCancelled(this::onSessionCancelled);

        currentSession.start(text, isLocal, model);
    }

    @FXML
    private void onStop() {
        if (currentSession != null) {
            currentSession.cancel();
            currentSession = null;
        }
    }

    // -------------------------------------------------------------------------
    // InferenceSession callbacks (all called on FX thread by InferenceSession)
    // -------------------------------------------------------------------------

    private void onSessionWaiting() {
        setInputEnabled(false);
        showTypingIndicator();
    }

    private void onSessionToken(String token) {
        if (streamingLabel == null) {
            // First token — swap typing indicator for a real bubble.
            removeTypingIndicator();
            streamingLabel = startStreamingBubble();
        }
        streamingLabel.setText(streamingLabel.getText() + token);
    }

    private void onSessionDone() {
        removeTypingIndicator();   // in case server sent done without any tokens
        streamingLabel = null;
        currentSession = null;
        setInputEnabled(true);
    }

    private void onSessionError(String reason) {
        removeTypingIndicator();
        streamingLabel = null;
        currentSession = null;
        appendErrorBubble(reason);
        setInputEnabled(true);
    }

    private void onSessionCancelled() {
        removeTypingIndicator();
        streamingLabel = null;
        currentSession = null;
        appendNotice("Response cancelled");
        setInputEnabled(true);
    }

    // -------------------------------------------------------------------------
    // Input enable / disable
    // -------------------------------------------------------------------------

    private void setInputEnabled(boolean enabled) {
        messageInput.setDisable(!enabled);
        sendBtn.setVisible(enabled);
        stopBtn.setVisible(!enabled);
    }

    // -------------------------------------------------------------------------
    // Typing indicator
    // -------------------------------------------------------------------------

    private void showTypingIndicator() {
        typingRow = new HBox();
        typingRow.setAlignment(Pos.CENTER_LEFT);

        VBox bubble = new VBox();
        bubble.getStyleClass().add("message-bubble-expert");

        Label dots = new Label("●");
        dots.getStyleClass().add("typing-indicator");
        bubble.getChildren().add(dots);
        typingRow.getChildren().add(bubble);
        chatHistory.getChildren().add(typingRow);

        int[] count = {0};
        typingTimeline = new Timeline(new KeyFrame(Duration.millis(400), e -> {
            count[0] = (count[0] + 1) % 4;
            dots.setText(switch (count[0]) {
                case 1  -> "●  ●";
                case 2  -> "●  ●  ●";
                default -> "●";
            });
        }));
        typingTimeline.setCycleCount(Timeline.INDEFINITE);
        typingTimeline.play();
    }

    private void removeTypingIndicator() {
        if (typingTimeline != null) { typingTimeline.stop(); typingTimeline = null; }
        if (typingRow      != null) { chatHistory.getChildren().remove(typingRow); typingRow = null; }
    }

    // -------------------------------------------------------------------------
    // Chat bubble helpers
    // -------------------------------------------------------------------------

    /** Creates an empty expert bubble, adds it to history, and returns its text label. */
    private Label startStreamingBubble() {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);

        VBox bubble = new VBox();
        bubble.getStyleClass().add("message-bubble-expert");

        Label label = new Label();
        label.setWrapText(true);
        bubble.getChildren().add(label);
        row.getChildren().add(bubble);
        chatHistory.getChildren().add(row);

        return label;
    }

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

    private void appendErrorBubble(String message) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);

        VBox bubble = new VBox();
        bubble.getStyleClass().add("message-bubble-error");

        Label label = new Label("Error: " + message);
        label.setWrapText(true);
        bubble.getChildren().add(label);
        row.getChildren().add(bubble);
        chatHistory.getChildren().add(row);
    }

    private void appendNotice(String text) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER);

        Label notice = new Label(text);
        notice.getStyleClass().add("message-notice");
        row.getChildren().add(notice);
        chatHistory.getChildren().add(row);
    }

    // ── Session list demo content ─────────────────────────────

    private void loadChat(String sessionName) {
        chatHistory.getChildren().clear();
        if (sessionName.equals("Greek Recipes")) {
            addExpertMessage("You have to mix yogurt, grated cucumber a lot of dill, olive oil and garlic.");
        } else if (sessionName.equals("How to Make Tomatoes Grow")) {
            addExpertMessage("Tomatoes grow best in full sunlight.");
            addExpertMessage("Water deeply about 2-3 times per week.");
        }
    }

    private void addExpertMessage(String text) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);

        VBox bubble = new VBox();
        bubble.getStyleClass().add("message-bubble-expert");

        Label label = new Label(text);
        label.setWrapText(true);
        bubble.getChildren().add(label);
        row.getChildren().add(bubble);
        chatHistory.getChildren().add(row);
    }

    // ── APP-MW-5 (#15): Workspace control handlers ───────────

    @FXML
    private void onSwitchExpert() {
        Navigator.showExpertSelection();
    }

    @FXML
    private void onOpenSettings() {
        Navigator.showSettings();
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
}
