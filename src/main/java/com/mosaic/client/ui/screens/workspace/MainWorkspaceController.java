package com.mosaic.client.ui.screens.workspace;

import com.mosaic.client.Navigator;
import com.mosaic.client.db.dao.ChatMessageDao;
import com.mosaic.client.db.dao.ChatSessionDao;
import com.mosaic.client.db.model.ChatMessage;
import com.mosaic.client.db.model.ChatSession;

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
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

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
            private TextField textField;

            @Override
            protected void updateItem(ChatSession item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else if (isEditing()) {
                    setText(null);
                    setGraphic(textField);
                } else {
                    setText(item.getTopic());
                    setGraphic(null);
                }
            }

            {
                // Double-click to start editing the session name
                setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY
                            && event.getClickCount() == 2
                            && !isEmpty()) {
                        startEdit();
                    }
                });
            }

            @Override
            public void startEdit() {
                super.startEdit();
                ChatSession item = getItem();
                if (item == null) return;

                textField = new TextField(item.getTopic());
                textField.getStyleClass().add("session-rename-field");

                // Commit on Enter, cancel on Escape
                textField.setOnKeyPressed(e -> {
                    if (e.getCode() == KeyCode.ENTER) {
                        commitRename(item, textField.getText().trim());
                    } else if (e.getCode() == KeyCode.ESCAPE) {
                        cancelEdit();
                    }
                });

                // Commit on focus lost
                textField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                    if (!isFocused) {
                        commitRename(item, textField.getText().trim());
                    }
                });

                setText(null);
                setGraphic(textField);
                textField.selectAll();
                textField.requestFocus();
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                ChatSession item = getItem();
                setText(item != null ? item.getTopic() : null);
                setGraphic(null);
            }

            private void commitRename(ChatSession item, String newName) {
                if (newName.isEmpty()) {
                    cancelEdit();
                    return;
                }
                try {
                    sessionDao.updateTopic(item.getSessionId(), newName);
                    item.setTopic(newName);
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
                cancelEdit();
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

        // Create a new session if none is active
        if (currentSession == null) {
            try {
                // Derive topic from first message (truncated to 100 chars per design doc)
                String topic = text.length() > 100 ? text.substring(0, 100) : text;
                currentSession = sessionDao.create(topic);
                loadSessionList();
                sessionListView.getSelectionModel().select(currentSession);
            } catch (SQLException e) {
                e.printStackTrace();
                return;
            }
        }

        // Persist the message to the database
        try {
            String[] expert = Navigator.getActiveExpert();
            String adapterId = (expert != null) ? expert[3] : null; // adapterFile as ID
            ChatMessage msg = new ChatMessage(currentSession.getSessionId(), "User", text, adapterId);
            messageDao.create(msg);
        } catch (SQLException e) {
            e.printStackTrace();
        }

        appendUserMessage(text);
        messageInput.clear();
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
        // Clear context only clears the visual display, not the database
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
                   // Delete session from database (cascade deletes messages)
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
