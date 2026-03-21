package com.mosaic.client.ui.screens.settings;

import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import com.mosaic.client.Navigator;

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

    // ── Privacy & Sharing ────────────────────────────────────
    @FXML private ToggleButton shareAnonymousDataToggle;
    @FXML private ToggleButton localOnlyModeToggle;

    // ── Network Configuration ────────────────────────────────
    @FXML private ComboBox<String> networkModeCombo;
    @FXML private TextArea         bootstrapNodesArea;

    // ── Adapter Management ───────────────────────────────────
    @FXML private CheckBox  bluetoothAdapterCheck;
    @FXML private CheckBox  wifiAdapterCheck;
    @FXML private CheckBox  ethernetAdapterCheck;
    @FXML private TextField customAdapterField;

    @FXML
    public void initialize() {
        // Populate network mode options
        networkModeCombo.getItems().addAll("Automatic", "Manual", "Offline");
        networkModeCombo.setValue("Automatic");

        // Default values
        shareAnonymousDataToggle.setSelected(false);
        localOnlyModeToggle.setSelected(false);
        bootstrapNodesArea.setPromptText("e.g. 192.168.1.1:4001, peer.example.com:4001");

        bluetoothAdapterCheck.setSelected(true);
        wifiAdapterCheck.setSelected(true);
        ethernetAdapterCheck.setSelected(false);
        customAdapterField.setPromptText("Enter custom adapter name");

        shareAnonymousDataToggle.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
        shareAnonymousDataToggle.setText(isSelected ? "On" : "Off");
        });

        localOnlyModeToggle.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
        localOnlyModeToggle.setText(isSelected ? "On" : "Off");
        });
    }

    @FXML
    private void onSave() {
        System.out.println("=== Settings Saved ===");
        System.out.println("[Privacy] Share anonymous data : " + shareAnonymousDataToggle.isSelected());
        System.out.println("[Privacy] Local-only mode      : " + localOnlyModeToggle.isSelected());
        System.out.println("[Network] Mode                 : " + networkModeCombo.getValue());
        System.out.println("[Network] Bootstrap nodes      : " + bootstrapNodesArea.getText().trim());
        System.out.println("[Adapter] Bluetooth            : " + bluetoothAdapterCheck.isSelected());
        System.out.println("[Adapter] Wi-Fi                : " + wifiAdapterCheck.isSelected());
        System.out.println("[Adapter] Ethernet             : " + ethernetAdapterCheck.isSelected());
        System.out.println("[Adapter] Custom               : " + customAdapterField.getText().trim());
 
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Settings");
        alert.setHeaderText(null);
        alert.setContentText("Settings saved successfully.");
        alert.showAndWait();
    }

    @FXML
    private void onCancel() {
        Navigator.showWorkspace();
    }

    @FXML
    private void onAddAdapter() {
        String name = customAdapterField.getText().trim();
        if (name.isEmpty()) return;
        System.out.println("[Adapter] Add requested for: " + name);
        customAdapterField.clear();
    }

    @FXML
    private void onRemoveAdapter() {
        System.out.println("[Adapter] Remove requested");
    }

}
