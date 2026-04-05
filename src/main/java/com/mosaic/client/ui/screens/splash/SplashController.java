package com.mosaic.client.ui.screens.splash;

import com.mosaic.client.ConnectionMonitor;
import com.mosaic.client.Navigator;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

public class SplashController {

    @FXML private Label  statusLabel;
    @FXML private Button retryBtn;

    @FXML
    public void initialize() {
        // Network availability is already known — MosaicApp.init() ran before the UI appeared.
        updateNetworkUI(Navigator.isNetworkAvailable());

        // Subscribe so the label updates automatically if the server comes up later.
        ConnectionMonitor mon = Navigator.getConnectionMonitor();
        if (mon != null) {
            mon.onConnectionStateChanged(state -> {
                boolean online = state == ConnectionMonitor.State.CONNECTED
                              || state == ConnectionMonitor.State.DEGRADED;
                updateNetworkUI(online);
            });
        }
    }

    // -------------------------------------------------------------------------
    // Network UI helpers
    // -------------------------------------------------------------------------

    private void updateNetworkUI(boolean online) {
        if (online) {
            statusLabel.setText("Network connected — all features available");
            statusLabel.getStyleClass().remove("splash-status-offline");
            if (!statusLabel.getStyleClass().contains("splash-status-connected")) {
                statusLabel.getStyleClass().add("splash-status-connected");
            }
            retryBtn.setVisible(false);
        } else {
            statusLabel.setText("Network server unavailable — remote inference and peer discovery will be off");
            statusLabel.getStyleClass().remove("splash-status-connected");
            if (!statusLabel.getStyleClass().contains("splash-status-offline")) {
                statusLabel.getStyleClass().add("splash-status-offline");
            }
            retryBtn.setVisible(true);
        }
    }

    // -------------------------------------------------------------------------
    // Button handlers
    // -------------------------------------------------------------------------

    @FXML
    private void onRetry() {
        // Disable for ~3.5 s — ConnectionMonitor polls every 3 s, so if the server comes
        // up it will fire onConnectionStateChanged automatically and update the label.
        retryBtn.setDisable(true);
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(3_500); } catch (InterruptedException ignored) {}
            Platform.runLater(() -> retryBtn.setDisable(false));
        });
    }

    @FXML
    private void onGetStarted() {
        Navigator.showWorkspace();
    }
}
