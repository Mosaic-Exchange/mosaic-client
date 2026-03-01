package com.mosaic.client;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;

public class MainLayoutController {

    @FXML
    private StackPane contentArea;

    // TODO APP-2 (#6): Add sidebar/top nav bar with navigation buttons

    // TODO APP-MW-1 (#11): Implement workspace screen (chat panel)
    // TODO APP-MW-2 (#12): Implement message input area
    // TODO APP-MW-3 (#13): Implement chat history sidebar
    // TODO APP-MW-4 (#14): Implement expert info panel
    // TODO APP-MW-5 (#15): Implement connection status bar

    // TODO APP-4 (#8): Implement expert selection screen
    // TODO APP-5 (#9): Implement settings screen
    // TODO APP-6 (#10): Add global error/notification overlay

    public void showSplash() {
        loadScreen("/fxml/SplashScreen.fxml");
    }

    public void showWorkspace() {
        // TODO APP-MW-1..MW-5 (#11-15): Replace placeholder with real workspace FXML
        loadScreen("/fxml/SplashScreen.fxml");
    }

    public void showExpertSelection() {
        // TODO APP-4 (#8): Replace placeholder with expert selection FXML
        loadScreen("/fxml/SplashScreen.fxml");
    }

    public void showSettings() {
        // TODO APP-5 (#9): Replace placeholder with settings FXML
        loadScreen("/fxml/SplashScreen.fxml");
    }

    private void loadScreen(String fxmlPath) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Parent screen = loader.load();
            contentArea.getChildren().setAll(screen);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
