package com.mosaic.client.ui.screens.workspace;

import com.mosaic.client.Navigator;
import com.mosaic.client.db.dao.ChatMessageDao;
import com.mosaic.client.db.dao.ChatSessionDao;
import com.mosaic.client.db.model.ChatMessage;
import com.mosaic.client.db.model.ChatSession;
import com.mosaic.client.service.LLMServer;
import com.mosaic.client.service.NetworkManager;
import com.mosaic.client.ui.screens.expert.Expert;

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
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import org.rumor.service.ServiceHandle;

import java.util.Optional;

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

    /** LLM server state */
    ReadOnlyObjectProperty<LLMServer.State> llmServerState = NetworkManager.getInstance().llmServerStateProperty();

    @FXML private ListView<ChatSession> sessionListView;

    @FXML private ScrollPane chatScrollPane;
    @FXML private VBox       chatHistory;
    
    /** Input area. */
    @FXML private TextArea   messageInput;
    
    // Detail panel
    @FXML private Label headerExpertName;
    @FXML private Label headerExpertSource;
    @FXML private Label headerExpertMode;
    @FXML private Label metaExpertName;
    @FXML private Label metaExpertDomain;
    @FXML private Label metaExpertSource;
    @FXML private Label metaAdapterFile;
    @FXML private Label metaExpertStatus;
    
    // Buttons
    @FXML private Button clearContextBtn;
    @FXML private Button newSessionBtn;
    @FXML private Button endSessionBtn;
    @FXML private Button switchExpertBtn;
    @FXML private Button settingsBtn;
    @FXML private Button sendBtn;
    
    /** Overlay message (loading) */
    @FXML private Label inputAreaOverlay;

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
            private final HBox cellBox = new HBox(4);
            private final Label topicLabel = new Label();
            private final Button renameBtn = new Button("M");
            private final Button deleteBtn = new Button("X");

            {
                topicLabel.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(topicLabel, Priority.ALWAYS);
                topicLabel.getStyleClass().add("session-topic-label");

                renameBtn.getStyleClass().add("session-rename-btn");
                renameBtn.setMaxSize(20, 20);
                renameBtn.setMinSize(20, 20);
                HBox.setHgrow(renameBtn, Priority.NEVER);

                deleteBtn.getStyleClass().add("session-delete-btn");
                deleteBtn.setMaxSize(20, 20);
                deleteBtn.setMinSize(20, 20);
                HBox.setHgrow(deleteBtn, Priority.NEVER);

                renameBtn.setOnAction(e -> {
                    ChatSession session = getItem();
                    if (session == null) return;
                    TextInputDialog dialog = new TextInputDialog(session.getTopic());
                    dialog.setTitle("Rename Session");
                    dialog.setHeaderText(null);
                    dialog.setContentText("New name:");
                    Optional<String> result = dialog.showAndWait();
                    result.ifPresent(newName -> {
                        if (!newName.trim().isEmpty()) {
                            try {
                                sessionDao.updateTopic(session.getSessionId(), newName.trim());
                                loadSessionList();
                            } catch (SQLException ex) {
                                ex.printStackTrace();
                            }
                        }
                    });
                });

                deleteBtn.setOnAction(e -> {
                    ChatSession session = getItem();
                    if (session == null) return;
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                            "Delete session \"" + session.getTopic() + "\"?",
                            ButtonType.OK, ButtonType.CANCEL);
                    confirm.setTitle("Delete Session");
                    confirm.setHeaderText(null);
                    confirm.showAndWait()
                           .filter(btn -> btn == ButtonType.OK)
                           .ifPresent(btn -> {
                               try {
                                   sessionDao.delete(session.getSessionId());
                                   if (currentSession != null
                                           && currentSession.getSessionId() == session.getSessionId()) {
                                       currentSession = null;
                                       Navigator.setActiveSession(null);
                                       chatHistory.getChildren().clear();
                                       messageInput.clear();
                                   }
                                   loadSessionList();
                               } catch (SQLException ex) {
                                   ex.printStackTrace();
                               }
                           });
                });

                cellBox.setAlignment(Pos.CENTER_LEFT);
                cellBox.getChildren().addAll(topicLabel, renameBtn, deleteBtn);
            }

            @Override
            protected void updateItem(ChatSession item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    topicLabel.setText(item.getTopic());
                    setGraphic(cellBox);
                    setText(null);
                }
            }
        });

        loadSessionList();

        sessionListView.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldVal, newVal) -> {
                if (newVal != null) {
                    Navigator.setActiveSession(newVal);
                    loadChat(newVal);
                }
            }
        );

        // Restore active session from Navigator if it exists
        ChatSession active = Navigator.getActiveSession();
        if (active != null) {
            for (ChatSession s : sessionItems) {
                if (s.getSessionId() == active.getSessionId()) {
                    sessionListView.getSelectionModel().select(s);
                    break;
                }
            }
        }

        // APP-MW-4 (#14): Load expert from current selections, or fall back to default Gardening Expert.
        ReadOnlyObjectProperty<Expert> activeExpert = Navigator.activeExpertProperty();

        // Bind Header Labels
        headerExpertName.textProperty().bind(activeExpert.flatMap(Expert::nameProperty));
        headerExpertSource.textProperty().bind(activeExpert.flatMap(Expert::sourceProperty).map(Object::toString));
        headerExpertMode.setText("Inference");

        // Bind Metadata Panel Labels
        metaExpertName.textProperty().bind(activeExpert.flatMap(Expert::nameProperty));
        metaExpertDomain.textProperty().bind(activeExpert.flatMap(Expert::domainProperty));
        metaExpertSource.textProperty().bind(activeExpert.flatMap(Expert::sourceProperty).map(Object::toString));
        metaAdapterFile.textProperty().bind(activeExpert.flatMap(Expert::adapterFileProperty));
        metaExpertStatus.textProperty().bind(activeExpert.flatMap(Expert::statusProperty).map(Object::toString));

        // Reactive styling for the status label in Workspace
        activeExpert.flatMap(Expert::statusProperty).addListener((obs, oldStatus, newStatus) -> {
            metaExpertStatus.getStyleClass().removeAll("expert-status-connected", "expert-status-disconnected", "expert-status-remote");
            if (newStatus == Expert.Status.LOADED) {
                metaExpertStatus.getStyleClass().add("expert-status-connected");
            } else if (newStatus == Expert.Status.UNLOADED) {
                metaExpertStatus.getStyleClass().add("expert-status-disconnected");
            } else if (newStatus == Expert.Status.REMOTE) {
                metaExpertStatus.getStyleClass().add("expert-status-remote");
            }
        });

        if (activeExpert.get() == null) {
            Navigator.setActiveExpert(Expert.BASE_MODEL);
        }

        // Disable the chat panel if the current adapter is local, and the server is unavailable.
        llmServerState.addListener(
                (observable, oldValue, newValue) -> {
                    updateChatPanel(newValue);
                }
        );

        // Initial state
        updateChatPanel(llmServerState.get());
    }

    /** Update the chat panel according to server state. */
    private void updateChatPanel(LLMServer.State state) {
        Expert active = Navigator.getActiveExpert();
        assert active != null : "Active adapter is null.";

        setChatPanelDisabled(
                active.getSource() == Expert.Source.LOCAL &&
                        state != LLMServer.State.CONNECTED
        );

        switch (state) {
            case CONNECTING -> inputAreaOverlay.setText("Connecting to the LLM server...");
            case DISCONNECTED -> inputAreaOverlay.setText("LLM server disconnected. Try restarting the app, or using a remote expert.");
            case CONNECTED -> inputAreaOverlay.setText("");
        }
    }

    public synchronized void setChatPanelDisabled(boolean disabled) {
        messageInput.setDisable(disabled);
        sendBtn.setDisable(disabled);
        inputAreaOverlay.setDisable(!disabled);
    }

    // ── AC3: Send button handler ─────────────────────────────
    @FXML
    private void onSend() {
        String text = messageInput.getText().trim();
        if (text.isEmpty()) return;

        // Ignore if an inference is already in progress
        if (activeInferenceHandle != null) return;

        blockInput();

        // Create a new session if none is active
        if (currentSession == null) {
            try {
                String topic = text.length() > 100 ? text.substring(0, 100) : text;
                currentSession = sessionDao.create(topic);
                loadSessionList();
                sessionListView.getSelectionModel().select(currentSession);
            } catch (SQLException e) {
                e.printStackTrace();
                unblockInput();
                return;
            }
        }

        // Persist user message
        try {
            Expert expert = Navigator.getActiveExpert();
            String adapterFilePath = (expert != null && expert != Expert.BASE_MODEL) ? expert.getAdapterFile() : null;
            ChatMessage msg = new ChatMessage(currentSession.getSessionId(), "User", text, adapterFilePath);
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

        Expert expert = Navigator.getActiveExpert();
        boolean remote = expert != null && expert.getSource() == Expert.Source.REMOTE;

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
                unblockInput();
                persistAssistantMessage(responseText.toString());
            }

            @Override
            public void onError(String reason) {
                activeInferenceHandle = null;
                unblockInput();
                if (responseText.isEmpty()) {
                    responseLabel.setText("[Error: " + reason + "]");
                } else {
                    responseLabel.setText(responseText + "\n[Error: " + reason + "]");
                }
            }

            @Override
            public void onCancelled() {
                activeInferenceHandle = null;
                unblockInput();
                if (responseText.isEmpty()) {
                    responseLabel.setText("[Cancelled]");
                } else {
                    responseLabel.setText(responseText + "\n[Cancelled]");
                }
            }
        };

        if (remote) {
            activeInferenceHandle = net.inferRemote(prompt, callback);
        } else if (expert != null) {
            // BASE_MODEL has serverSideId = "", so it is ignored.
            activeInferenceHandle = net.inferLocal(prompt, expert.getServerSideId(), callback);
        } else {
            // This shouldn't happen, but just in case (and so IDE doesn't complain).
            activeInferenceHandle = net.inferLocal(prompt, "", callback);
        }
    }

    private void persistAssistantMessage(String content) {
        if (currentSession == null || content.isEmpty()) return;
        try {
            Expert expert = Navigator.getActiveExpert();
            String adapterFilePath = (expert != null && expert != Expert.BASE_MODEL) ? expert.getAdapterFile() : null;
            ChatMessage msg = new ChatMessage(
                    currentSession.getSessionId(), "Assistant", content, adapterFilePath);
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
            unblockInput();
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
                   Navigator.setActiveSession(null);
                   chatHistory.getChildren().clear();
                   messageInput.clear();
                   loadSessionList();
               });
    }

    // ── Helpers ──────────────────────────────────────────────
    
    private void blockInput() {
        sessionListView.setDisable(true);
        messageInput.setDisable(true);
        clearContextBtn.setDisable(true);
        newSessionBtn.setDisable(true);
        endSessionBtn.setDisable(true);
        switchExpertBtn.setDisable(true);
        settingsBtn.setDisable(true);
        sendBtn.setDisable(true);
    }

    private void unblockInput() {
        sessionListView.setDisable(false);
        messageInput.setDisable(false);
        clearContextBtn.setDisable(false);
        newSessionBtn.setDisable(false);
        endSessionBtn.setDisable(false);
        switchExpertBtn.setDisable(false);
        settingsBtn.setDisable(false);
        sendBtn.setDisable(false);
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
