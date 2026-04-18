package com.mosaic.client.ui.screens.workspace;

import com.mosaic.client.Navigator;
import com.mosaic.client.db.dao.ChatMessageDao;
import com.mosaic.client.db.dao.ChatSessionDao;
import com.mosaic.client.db.model.ChatMessage;
import com.mosaic.client.db.model.ChatSession;
import com.mosaic.client.service.LLMServer;
import com.mosaic.client.service.NetworkManager;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import org.rumor.service.ServiceHandle;

import java.sql.SQLException;
import java.util.List;

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

    // ── DAOs ─────────────────────────────────────────────────
    private final ChatSessionDao sessionDao = new ChatSessionDao();
    private final ChatMessageDao messageDao = new ChatMessageDao();

    /** The currently active session (null when no session is open). */
    private ChatSession currentSession;

    /** Handle for the currently running inference request (null when idle). */
    private volatile ServiceHandle activeInferenceHandle;

    /** Observable list backing the sidebar ListView. */
    private ObservableList<ChatSession> sessionItems;

    // ── MW-2 fields ──────────────────────────────────────────
    @FXML
    private ListView<ChatSession> sessionListView;


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
    @FXML private Button clearContextBtn;
    @FXML private Button sendBtn;

    @FXML
    public void initialize() {
        // AC3: Enter sends; Shift+Enter inserts a newline.
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
        chatHistory.heightProperty().addListener(
                (obs, oldHeight, newHeight) -> chatScrollPane.setVvalue(1.0));

        // ── Session list setup (DB-backed) ───────────────────
        sessionListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ChatSession item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getTopic());
            }
        });

        loadSessionList();

        sessionListView.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldVal, newVal) -> {
                if (newVal != null) {
                    loadChat(newVal);
                }
            }
        );

        // APP-MW-4 (#14): Load expert from current selections, or fall back to default Gardening Expert.
        String[] expert = Navigator.getActiveExpert();
        if (expert != null) {
            loadActiveExpert(expert[0], expert[1], expert[2], expert[3], expert[4]);
        } else {
            loadActiveExpert("Gardening Expert", "Gardening", "Local",
                             "gardening_expert.gguf", "Connected");
        }

        // Disable the chat panel if the current adapter is local, and the server is unavailable.
        ReadOnlyObjectProperty<LLMServer.State> serverState = NetworkManager.getInstance().llmServerStateProperty();
        serverState.addListener((observable, oldValue, newValue) -> {
            setChatPanelDisabled(
                    (Navigator.getActiveExpert() == null ||
                            !Navigator.getActiveExpert()[2].equals("Remote")) &&
                            newValue != LLMServer.State.CONNECTED
            );
        });

        // Disable the chat panel when switching to a local adapter, and the server is unreachable.
        headerExpertSource.textProperty().addListener(
                (observable, oldValue, newValue) -> {
                    setChatPanelDisabled(
                            !newValue.equals("Remote") &&
                                    serverState.get() != LLMServer.State.CONNECTED
                    );
                }
        );

        // Initial state
        setChatPanelDisabled(serverState.get() != LLMServer.State.CONNECTED);
    }

    public synchronized void setChatPanelDisabled(boolean disabled) {
        messageInput.setDisable(disabled);
        sendBtn.setDisable(disabled);
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

        // Ignore if an inference is already in progress
        if (activeInferenceHandle != null) return;

        // Create a new session if none is active
        if (currentSession == null) {
            try {
                String topic = text.length() > 100 ? text.substring(0, 100) : text;
                currentSession = sessionDao.create(topic);
                loadSessionList();
                sessionListView.getSelectionModel().select(currentSession);
            } catch (SQLException e) {
                e.printStackTrace();
                return;
            }
        }

        // Persist user message
        try {
            String[] expert = Navigator.getActiveExpert();
            String adapterId = (expert != null) ? expert[3] : null;
            ChatMessage msg = new ChatMessage(currentSession.getSessionId(), "User", text, adapterId);
            messageDao.create(msg);
        } catch (SQLException e) {
            e.printStackTrace();
        }

        appendUserMessage(text);
        messageInput.clear();

        // Dispatch inference and stream tokens into a response bubble
        runInference(text);
    }

    /**
     * Dispatches an inference request and streams tokens into the chat.
     * Uses local inference if the expert source is "Local", remote otherwise.
     */
    private void runInference(String prompt) {
        NetworkManager net = NetworkManager.getInstance();

        // Create the response bubble and label up front (on FX thread)
        Label responseLabel = new Label();
        responseLabel.setWrapText(true);
        VBox bubble = new VBox(responseLabel);
        bubble.getStyleClass().add("message-bubble-expert");
        HBox row = new HBox(bubble);
        row.setAlignment(Pos.CENTER_LEFT);
        chatHistory.getChildren().add(row);

        StringBuilder responseText = new StringBuilder();

        String[] expert = Navigator.getActiveExpert();
        boolean remote = expert != null && "Remote".equals(expert[2]);

        NetworkManager.InferenceCallback callback = new NetworkManager.InferenceCallback() {
            @Override
            public void onToken(String token) {
                // Already on FX thread (NetworkManager uses Platform.runLater)
                responseText.append(token);
                responseLabel.setText(responseText.toString());
            }

            @Override
            public void onComplete() {
                activeInferenceHandle = null;
                persistAssistantMessage(responseText.toString());
            }

            @Override
            public void onError(String reason) {
                activeInferenceHandle = null;
                if (responseText.isEmpty()) {
                    responseLabel.setText("[Error: " + reason + "]");
                } else {
                    responseLabel.setText(responseText + "\n[Error: " + reason + "]");
                }
            }

            @Override
            public void onCancelled() {
                activeInferenceHandle = null;
                if (responseText.isEmpty()) {
                    responseLabel.setText("[Cancelled]");
                } else {
                    responseLabel.setText(responseText + "\n[Cancelled]");
                }
            }
        };

        if (remote) {
            activeInferenceHandle = net.inferRemote(prompt, callback);
        } else {
            activeInferenceHandle = net.inferLocal(prompt, callback);
        }
    }

    private void persistAssistantMessage(String content) {
        if (currentSession == null || content.isEmpty()) return;
        try {
            String[] expert = Navigator.getActiveExpert();
            String adapterId = (expert != null) ? expert[3] : null;
            ChatMessage msg = new ChatMessage(
                    currentSession.getSessionId(), "Assistant", content, adapterId);
            messageDao.create(msg);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ── APP-MW-5 (#15): Workspace control handlers ───────────

    @FXML
    private void onSwitchExpert() {
        cancelActiveInference();
        Navigator.showExpertSelection();
    }

    @FXML
    private void onOpenSettings() {
        cancelActiveInference();
        Navigator.showSettings();
    }

    private void cancelActiveInference() {
        ServiceHandle h = activeInferenceHandle;
        if (h != null) {
            h.cancel();
            activeInferenceHandle = null;
        }
    }

    @FXML
    private void onClearContext() {
        cancelActiveInference();
        chatHistory.getChildren().clear();
    }

    @FXML
    private void onEndSession() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "End this session? The session and all its messages will be deleted.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("End Session");
        confirm.setHeaderText(null);
        confirm.showAndWait()
               .filter(btn -> btn == ButtonType.OK)
               .ifPresent(btn -> {
                   cancelActiveInference();
                   if (currentSession != null) {
                       try {
                           sessionDao.delete(currentSession.getSessionId());
                       } catch (SQLException e) {
                           e.printStackTrace();
                       }
                   }
                   currentSession = null;
                   chatHistory.getChildren().clear();
                   messageInput.clear();
                   loadSessionList();
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

    /**
     * Loads messages from the database for the given session and displays them.
     */
    private void loadChat(ChatSession session) {
        currentSession = session;
        chatHistory.getChildren().clear();
        try {
            List<ChatMessage> messages = messageDao.findBySessionId(session.getSessionId());
            for (ChatMessage msg : messages) {
                if ("User".equals(msg.getRole())) {
                    addUserMessage(msg.getContent());
                } else {
                    addExpertMessage(msg.getContent());
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Reloads the session list from the database into the sidebar ListView.
     */
    private void loadSessionList() {
        try {
            List<ChatSession> sessions = sessionDao.findAll();
            sessionItems = FXCollections.observableArrayList(sessions);
            sessionListView.setItems(sessionItems);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a new empty session and selects it in the sidebar.
     */
    @FXML
    private void onNewSession() {
        try {
            currentSession = sessionDao.create("New Chat");
            loadSessionList();
            // Select the newly created session
            for (ChatSession s : sessionItems) {
                if (s.getSessionId() == currentSession.getSessionId()) {
                    sessionListView.getSelectionModel().select(s);
                    break;
                }
            }
            chatHistory.getChildren().clear();
            messageInput.clear();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void addUserMessage(String text) {
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
}
