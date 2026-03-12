package com.mosaic.client.ui.screens.workspace;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;
import javafx.geometry.Insets;

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
    private ListView<String> sessionListView;

    @FXML
    private VBox chatContainer;

    @FXML
    public void initialize() {
        // Layout initialised by FXML — no code needed here for #11.
        // Teammates: add your initialisation logic below.
        sessionListView.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldSession, newSession) -> {
                if (newSession != null) {
                    loadChat(newSession);
                }
            }
        );

        // load first chat
        if (!sessionListView.getItems().isEmpty()) {
            sessionListView.getSelectionModel().selectFirst();
        }
        chatContainer.setPadding(new Insets(12, 16, 12, 16));
        chatContainer.setSpacing(8);

    }
    
    private void loadChat(String sessionName){
        chatContainer.getChildren().clear();

        if (sessionName.equals("Greek Recipes")){
            addUserMessage("How do I make tzaziki?");
            addExpertMessage("You have to mix yogurt, grated cucumber a lot of dill, olive oil and garlic.");
        } else if (sessionName.equals("How to Make Tomatoes Grow")) {
            addUserMessage("How do I make sure my tomatoes are growing?");
            addExpertMessage("Tomatoes grow best in full sunlight.");
            addUserMessage("How often should I water them?");
            addExpertMessage("Water deeply bout 2-3 times per week.");

        }
    }
    private void addUserMessage(String text) {

        Label label = new Label(text);
        label.getStyleClass().add("message-bubble-user");

        HBox box = new HBox(label);
        box.setAlignment(Pos.CENTER_RIGHT);

        chatContainer.getChildren().add(box);
    }

    private void addExpertMessage(String text) {

        Label label = new Label(text);
        label.getStyleClass().add("message-bubble-expert");

        HBox box = new HBox(label);
        box.setAlignment(Pos.CENTER_LEFT);

        chatContainer.getChildren().add(box);
    }
}
