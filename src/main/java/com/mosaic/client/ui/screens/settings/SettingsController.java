package com.mosaic.client.ui.screens.settings;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import com.mosaic.client.Navigator;
import com.mosaic.client.config.AppConfig;


public class SettingsController {

    // ── Privacy & Sharing ────────────────────────────────────
    @FXML private ToggleButton shareAnonymousDataToggle;
    @FXML private ToggleButton localOnlyModeToggle;

    // ── Network Configuration ────────────────────────────────
    @FXML private ComboBox<String> networkModeCombo;
    @FXML private TextField myIpField;
    @FXML private TextField myPortField;
    @FXML private TextField seedIpField;
    @FXML private TextField seedPortField;
    @FXML private Text      saveConfirmation;

    // ── Adapter Management ───────────────────────────────────
    @FXML private CheckBox  bluetoothAdapterCheck;
    @FXML private CheckBox  wifiAdapterCheck;
    @FXML private CheckBox  ethernetAdapterCheck;
    @FXML private TextField customAdapterField;

    private final AppConfig config = new AppConfig();

    @FXML
    public void initialize() {
        
        // Load persisted settings first
        config.load();
        // Populate network mode options
        networkModeCombo.getItems().addAll("Automatic", "Manual", "Offline");
        networkModeCombo.setValue(config.getNetworkMode());

        // Populate network fields from config (or defaults)
        myIpField.setText(config.getMyIp());
        myPortField.setText(config.getMyPort());
        seedIpField.setText(config.getSeedIp());
        seedPortField.setText(config.getSeedPort());

        // Load privacy toggles
        shareAnonymousDataToggle.setSelected(config.getShareAnon());
        localOnlyModeToggle.setSelected(config.getLocalOnly());

        // Load adapter checkboxes
        bluetoothAdapterCheck.setSelected(config.getBluetooth());
        wifiAdapterCheck.setSelected(config.getWifi());
        ethernetAdapterCheck.setSelected(config.getEthernet());

        // Load custom adapter
        customAdapterField.setText(config.getCustom());
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

        saveConfirmation.setVisible(false);

        // Validate 
        String myIp   = myIpField.getText().trim();
        String myPort = myPortField.getText().trim();
        String seedIp = seedIpField.getText().trim();
        String seedPort = seedPortField.getText().trim();

        if (myIp.isEmpty() || myPort.isEmpty() || seedIp.isEmpty() || seedPort.isEmpty()) {
            showError("All network fields are required.");
            return;
        }

        if (!isValidPort(myPort)) {
            showError("My Port must be a number between 1 and 65535.");
            return;
        }

        if (!isValidPort(seedPort)) {
            showError("Seed Port must be a number between 1 and 65535.");
            return;
        }

        // Save 
        try {
            config.save(
            myIp,
            myPort,
            seedIp,
            seedPort,
            shareAnonymousDataToggle.isSelected(),   // shareAnon
            localOnlyModeToggle.isSelected(),        // localOnly
            networkModeCombo.getValue(),             // networkMode
            bluetoothAdapterCheck.isSelected(),      // bluetooth
            wifiAdapterCheck.isSelected(),           // wifi
            ethernetAdapterCheck.isSelected(),       // ethernet
            customAdapterField.getText().trim()      // customAdapter
            );
        } catch (Exception e) {
            showError("Could not save settings: " + e.getMessage());
            return;
        }

         // Confirm 

        System.out.println("=== Settings Saved ===");
        System.out.println("[Network] My IP    : " + myIp);
        System.out.println("[Network] My Port  : " + myPort);
        System.out.println("[Network] Seed IP  : " + seedIp);
        System.out.println("[Network] Seed Port: " + seedPort);

        System.out.println("[Privacy] Share Anonymous: " + shareAnonymousDataToggle.isSelected());
        System.out.println("[Privacy] Local Only    : " + localOnlyModeToggle.isSelected());
        System.out.println("[Network Mode] : " + networkModeCombo.getValue());
        System.out.println("[Adapters] Bluetooth: " + bluetoothAdapterCheck.isSelected() +
                       ", WiFi: " + wifiAdapterCheck.isSelected() +
                       ", Ethernet: " + ethernetAdapterCheck.isSelected() +
                       ", Custom: " + customAdapterField.getText().trim());

        saveConfirmation.setFill(Color.web("#4caf50"));
        saveConfirmation.setText("Settings saved");
        saveConfirmation.setVisible(true);
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

    // ── Helpers ──────────────────────────────────────────────

    private boolean isValidPort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void showError(String message) {
        saveConfirmation.setFill(Color.web("#e53935"));
        saveConfirmation.setText(message);
        saveConfirmation.setVisible(true);
    }

    @FXML
    private void onReset() {
        myIpField.setText(AppConfig.DEFAULT_MY_IP);
        myPortField.setText(AppConfig.DEFAULT_MY_PORT);
        seedIpField.setText(AppConfig.DEFAULT_SEED_IP);
        seedPortField.setText(AppConfig.DEFAULT_SEED_PORT);

        shareAnonymousDataToggle.setSelected(false);
        shareAnonymousDataToggle.setText("Off");
        localOnlyModeToggle.setSelected(false);
        localOnlyModeToggle.setText("Off");

        networkModeCombo.setValue(AppConfig.DEFAULT_NETWORK_MODE);

        bluetoothAdapterCheck.setSelected(true);
        wifiAdapterCheck.setSelected(true);
        ethernetAdapterCheck.setSelected(false);
        customAdapterField.clear();

        saveConfirmation.setFill(Color.web("#4caf50"));
        saveConfirmation.setText("Reset to defaults, save to apply");
        saveConfirmation.setVisible(true);
    }

}
