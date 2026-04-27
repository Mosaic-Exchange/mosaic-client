package com.mosaic.client.ui.screens.settings;

import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import com.mosaic.client.AppConfig;
import com.mosaic.client.Navigator;
import com.mosaic.client.service.NetworkManager;
import javafx.application.Platform;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;

/**
 * Controller for the Settings screen.
 *
 * TODO APP-5 (#9) — Full implementation
 *   AC1: Add three sections: Privacy & Sharing, Network Configuration, Adapter Management
 *   AC2: Bind toggles / dropdowns / text areas to local state fields in this controller
 *   AC3: "Save" button → print field values to console or show a confirmation Alert
 *   AC4: "Cancel" button → Navigator.showWorkspace()
 *         (no changes are saved — document this choice in the PR description)
 *   AC5: No file/DB persistence required
 */
public class SettingsController {

    // ── Debug & Storage ──────────────────────────────────────
    @FXML private CheckBox   debugEnabledCheck;
    @FXML private TextField  dataDirField;
    @FXML private Button     browseDirBtn;

    // ── Network Configuration ────────────────────────────────
    @FXML private TextField  portField;
    @FXML private TextField  llmServerPortField;
    @FXML private ComboBox<String> nodeTypeCombo;
    @FXML private TextField  seedField;
    @FXML private Label      networkStatusLabel;

    // ── Error display ────────────────────────────────────────
    @FXML private Label      errorLabel;

    @FXML
    public void initialize() {
        // Load the current settings from the config file and pre-fill the fields
        AppConfig config = AppConfig.load();
 
        portField.setText(String.valueOf(config.port()));
        llmServerPortField.setText(String.valueOf(config.llmServerPort()));

        // Dropdown with only the valid options
        nodeTypeCombo.getItems().addAll("basic", "seed", "master");
        nodeTypeCombo.setValue(config.nodeType());

        seedField.setText(config.seed());
        debugEnabledCheck.setSelected(config.debugEnabled());
        dataDirField.setText(config.dataDir().toString());

        browseDirBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select Data Directory");
            File current = config.dataDir().toFile();
            if (current.exists()) dc.setInitialDirectory(current);
            File chosen = dc.showDialog(browseDirBtn.getScene().getWindow());
            if (chosen != null) {
                dataDirField.setText(chosen.getAbsolutePath());
            }
        });

        errorLabel.setVisible(false);
 
        updateNetworkStatus();
        Platform.runLater(() -> portField.getScene().getRoot().requestFocus());

    }

    private void updateNetworkStatus() {
        if (networkStatusLabel == null) return;
        NetworkManager net = NetworkManager.getInstance();
        if (net.isRunning()) {
            networkStatusLabel.setText("Connected — " + net.localId());
            networkStatusLabel.setStyle("-fx-text-fill: #4caf50;");
        } else {
            networkStatusLabel.setText("Disconnected");
            networkStatusLabel.setStyle("-fx-text-fill: #f44336;");
        }
    }

    @FXML
    private void onSave() {

        if (!validateAndApply()) return;
 
        AppConfig config = AppConfig.load();

        // Copy the validated UI values into the config object
        config.setPort(Integer.parseInt(portField.getText().trim()));
        config.setLlmServerPort(Integer.parseInt(llmServerPortField.getText().trim()));
        config.setNodeType(nodeTypeCombo.getValue());
        config.setSeed(seedField.getText().trim());
        config.setDebugEnabled(debugEnabledCheck.isSelected());
        config.setDataDir(dataDirField.getText().trim());

        // Write the config object to mosaic.yml
        try {
            config.save();
        } catch (IOException e) {
            showError("Could not write config file: " + e.getMessage());
            return;
        }

        // Also apply the network settings live so changes take effect immediately
        applyNetworkSettings(config);
 
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Settings");
        alert.setHeaderText(null);
        alert.setContentText("Settings saved successfully.");
        alert.showAndWait();
    }

    
    /**
     * Validates all fields. Returns true if everything is okay, false if not.
     * When false, the error label is shown with a message explaining what's wrong.
     */
    private boolean validateAndApply() {

        AppConfig current = AppConfig.load();
        StringBuilder errors = new StringBuilder();
        // ── port ──────────────────────────────────────────────
        int port = -1;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 0 || port > 65535) {
                errors.append("• Port must be a number between 0 and 65535.\n");
                port = -1;
            }
        } catch (NumberFormatException e) {
            errors.append("• Port must be a number.\n");
        }

        // ── llmServerPort ─────────────────────────────────────
        int llmPort = -1;
        try {
            llmPort = Integer.parseInt(llmServerPortField.getText().trim());
            if (llmPort < 0 || llmPort > 65535) {
                errors.append("• LLM Server Port must be a number between 0 and 65535.\n");
                llmPort = -1;
            }
        } catch (NumberFormatException e) {
            errors.append("• LLM Server Port must be a number.\n");
        }

        // ── port availability ─────────────────────────────────
        // Only check if the values parsed successfully
        if (port != -1 && port != current.port() && !isPortAvailable(port)) {
            errors.append("• Port ").append(port).append(" is already in use by another process.\n");
        }
        if (llmPort != -1 && llmPort != current.llmServerPort() && !isPortAvailable(llmPort)) {
            errors.append("• LLM Server Port ").append(llmPort).append(" is already in use by another process.\n");
        }

        // ── seed ───────────────────────────────────────────────
        String seed = seedField.getText().trim();
        if (!seed.isEmpty()) {
            if (!seed.matches("^\\d{1,3}(\\.\\d{1,3}){3}:\\d{1,5}$")) {
                errors.append("• Seed must be an IP address with a port, e.g. 127.0.0.1:7001\n");
            }
        }

        // ── dataDir ───────────────────────────────────────────
        String dataDir = dataDirField.getText().trim();
        if (dataDir.isEmpty()) {
            errors.append("• Data directory cannot be empty.\n");
        }

         // ── Show errors or proceed ────────────────────────────
        if (errors.length() > 0) {
            errorLabel.setText(errors.toString().trim());
            errorLabel.setVisible(true);
            return false;
        }

        errorLabel.setVisible(false);
        return true;
    }

    /**
     * Tries to open a ServerSocket on the given port.
     * If it succeeds, the port is free. If it throws, something else is using it.
     */
    private boolean isPortAvailable(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void applyNetworkSettings(AppConfig config) {
        NetworkManager net = NetworkManager.getInstance();
        net.stop();
        try {
            net.start(
                config.host(),
                config.port(),
                config.llmServerPort(),
                config.nodeType(),
                config.debugEnabled(),
                config.dataDir(),
                config.logDir(),
                config.seedAddresses()
            );
        } catch (Exception e) {
            showError("Settings saved, but failed to restart network: " + e.getMessage());
        }
        updateNetworkStatus();
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
    }
 
    @FXML
    private void onCancel() {
        Navigator.showWorkspace();
    }

    @FXML
    private void onResetDefaults() {
        portField.setText("7001");
        llmServerPortField.setText("4000");
        nodeTypeCombo.setValue("basic");
        seedField.setText("");
        debugEnabledCheck.setSelected(false);
        dataDirField.setText(System.getProperty("user.home") + "/.mosaic");
        errorLabel.setVisible(false);
    }

}
