package com.mosaic.client.ui.screens.expert;

import com.mosaic.client.Navigator;
import com.mosaic.client.service.AdapterMetadata;
import com.mosaic.client.service.NetworkManager;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import org.apache.commons.io.FileUtils;
import org.rumor.gossip.NodeId;
import org.rumor.service.ServiceHandle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Controller for the Expert Selection screen.
 *
 * APP-4 (#8) — Full implementation
 *   AC1: Filters panel (domain / source / availability dropdowns)
 *   AC2: ListView populated with hardcoded expert entries
 *   AC3: Selecting an expert updates the detail/preview panel on the right
 *   AC4: "Confirm" button → Navigator.showWorkspace()
 *   AC5: "Download" button → placeholder Alert
 */
public class ExpertSelectionController {

    // ── AC1: Filter controls ─────────────────────────────────
    @FXML private ComboBox<String> domainFilter;
    @FXML private ComboBox<String> sourceFilter;
    @FXML private ComboBox<String> availabilityFilter;

    // ── AC2: Expert list ─────────────────────────────────────
    @FXML private ListView<Expert> expertListView;

    // ── AC3: Detail panel labels ─────────────────────────────
    @FXML private VBox      detailCard;
    @FXML private TextField detailName;
    @FXML private TextField detailDomain;
    @FXML private Label detailSource;
    @FXML private Label detailAdapter;
    @FXML private Label detailStatus;

    // ── AC4 + AC5: Action buttons ────────────────────────────
    @FXML private Button confirmBtn;
    @FXML private Button downloadBtn;
    @FXML private Button addAdapterBtn;

    @FXML private ProgressBar downloadProgress;

    /** Currently selected expert. */
    private final ObjectProperty<Expert> selectedExpert = new SimpleObjectProperty<>();

    /** Master (unfiltered) list of experts. */
    private ObservableList<Expert> allExperts;

    /** Filtered view that the ListView displays. */
    private FilteredList<Expert> filteredExperts;

    /** Handle for an in-progress adapter download (null when idle). */
    private volatile ServiceHandle activeDownloadHandle;

    @FXML
    public void initialize() {
        // ── AC2: Local experts + network-discovered remote experts ─
        allExperts = FXCollections.observableArrayList();

        try {
            loadLocalAdapters();
        } catch (IOException e) {
            System.getLogger("ExpertSelectionController").log(
                    System.Logger.Level.ERROR,
                    "Loading local adapters failed: " + e.getMessage()
            );
            Alert alert = new Alert(
                    Alert.AlertType.ERROR,
                    "Loading local adapters failed (%s). Displaying only remote adapters.".formatted(e.getMessage())
            );
            alert.showAndWait();
        }

        // Merge remote adapters discovered via gossip
        loadRemoteAdapters();

        // ── AC1: Populate filter dropdowns ───────────────────
        domainFilter.setItems(FXCollections.observableArrayList(
                "All Domains", "Gardening", "Chess", "Remote"));
        domainFilter.setValue("All Domains");

        sourceFilter.setItems(FXCollections.observableArrayList(
                "All Sources", Expert.Source.LOCAL.toString(), Expert.Source.REMOTE.toString()));
        sourceFilter.setValue("All Sources");

        availabilityFilter.setItems(FXCollections.observableArrayList(
                "All", Expert.Status.LOADED.toString(), Expert.Status.UNLOADED.toString()));
        availabilityFilter.setValue("All");

        // Wire filter change listeners
        domainFilter.setOnAction(e -> applyFilters());
        sourceFilter.setOnAction(e -> applyFilters());
        availabilityFilter.setOnAction(e -> applyFilters());

        // ── AC2: Bind filtered list to ListView ──────────────
        filteredExperts = new FilteredList<>(allExperts, e -> true);
        expertListView.setItems(filteredExperts);

        // Custom cell to show expert name + domain
        expertListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Expert item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getName() + "  ·  " + item.getDomain());
                }
            }
        });

        // ── AC3: Selection listener updates detail panel ─────
        // Bind ListView selection to the selectedExpert property
        selectedExpert.bind(expertListView.getSelectionModel().selectedItemProperty());

        // Bind Detail Panel Labels using flatMap
        detailName.textProperty().bind(selectedExpert.flatMap(Expert::nameProperty).orElse("—"));
        detailDomain.textProperty().bind(selectedExpert.flatMap(Expert::domainProperty).orElse("—"));
        detailSource.textProperty().bind(selectedExpert.flatMap(Expert::sourceProperty).map(Object::toString).orElse("—"));
        detailAdapter.textProperty().bind(selectedExpert.flatMap(Expert::adapterFileProperty).orElse("—"));
        detailStatus.textProperty().bind(selectedExpert.flatMap(Expert::statusProperty).map(Object::toString).orElse("—"));

        // Reactive styling for the status label
        selectedExpert.flatMap(Expert::statusProperty).addListener((obs, oldStatus, newStatus) -> {
            detailStatus.getStyleClass().removeAll("expert-status-connected", "expert-status-disconnected", "expert-status-remote");
            if (newStatus == Expert.Status.LOADED) {
                detailStatus.getStyleClass().add("expert-status-connected");
            } else if (newStatus == Expert.Status.UNLOADED) {
                detailStatus.getStyleClass().add("expert-status-disconnected");
            } else if (newStatus == Expert.Status.REMOTE) {
                detailStatus.getStyleClass().add("expert-status-remote");
            }
        });

        confirmBtn.disableProperty().bind(selectedExpert.isNull());
        downloadBtn.disableProperty().bind(selectedExpert.isNull());
    }

    // ── AC1: Filter logic ────────────────────────────────────

    private void applyFilters() {
        String domain = domainFilter.getValue();
        String source = sourceFilter.getValue();
        String availability = availabilityFilter.getValue();

        filteredExperts.setPredicate(expert -> {
            if (domain != null && !"All Domains".equals(domain)
                    && !expert.getDomain().equals(domain)) {
                return false;
            }
            if (source != null && !"All Sources".equals(source)
                    && !expert.getSource().toString().equals(source)) {
                return false;
            }
            if (availability != null && !"All".equals(availability)
                    && !expert.getStatus().toString().equals(availability)) {
                return false;
            }
            return true;
        });

        // Clear selection when filters change, so detail panel resets
        expertListView.getSelectionModel().clearSelection();
    }

    // ── AC4: Confirm → go to workspace ───────────────────────

    @FXML
    private void onConfirm() {
        Expert currentSelected = selectedExpert.get();
        if (currentSelected != null) {
            Navigator.setActiveExpert(currentSelected);

            if (!currentSelected.isRemote() && !currentSelected.isLoaded()) {
                loadAdapter(currentSelected);
            }
        } else {
            Navigator.showWorkspace();
        }
    }

    private void loadAdapter(Expert currentSelected) {
        confirmBtn.getScene().getRoot().setDisable(true);

        NetworkManager.getInstance().registerAdapter(
                NetworkManager.getInstance().getAdaptersDir().resolve(currentSelected.getAdapterFile()),
                response -> {
                    confirmBtn.getScene().getRoot().setDisable(false);
                    if (response.error().isPresent()) {
                        Platform.runLater(() -> { new Alert(Alert.AlertType.ERROR, "Failed to register adapter: " + response.error().get()).showAndWait(); });
                        return;
                    }
                    System.getLogger("ExpertSelectionController").log(System.Logger.Level.INFO,
                            "Registered adapter with response: " + response);
                    currentSelected.load(response.adapterId());
                },
                throwable -> {
                    confirmBtn.getScene().getRoot().setDisable(false);
                    Platform.runLater(() -> { new Alert(Alert.AlertType.ERROR, "Error during adapter registration: " + throwable.getMessage()).showAndWait(); });
                    currentSelected.unload();
                }
        );
    }

    // ── AC5: Download adapter from remote peer ────────────────

    @FXML
    private void onDownload() {
        Expert currentSelected = selectedExpert.get();
        if (currentSelected == null || currentSelected.getSource() != Expert.Source.REMOTE) {
            new Alert(Alert.AlertType.INFORMATION, "Select a remote expert to download.",
                    ButtonType.OK).showAndWait();
            return;
        }
        if (activeDownloadHandle != null) {
            new Alert(Alert.AlertType.WARNING, "A download is already in progress.",
                    ButtonType.OK).showAndWait();
            return;
        }

        String adapterName = currentSelected.getAdapterFile();
        downloadBtn.setDisable(true);
        downloadBtn.setText("Downloading…");
        if (downloadProgress != null) {
            downloadProgress.setVisible(true);
            downloadProgress.setProgress(-1); // indeterminate
        }

        activeDownloadHandle = NetworkManager.getInstance().downloadAdapter(adapterName,
                new NetworkManager.AdapterDownloadCallback() {
                    @Override
                    public void onProgress(long bytesReceived) {
                        // Already on FX thread
                        if (downloadProgress != null) {
                            downloadProgress.setProgress(-1); // indeterminate until we know total
                        }
                    }

                    @Override
                    public void onComplete(Path outputPath) {
                        activeDownloadHandle = null;
                        resetDownloadButton();
                        new Alert(Alert.AlertType.INFORMATION,
                                "Adapter downloaded: " + outputPath.getFileName(),
                                ButtonType.OK).showAndWait();
                    }

                    @Override
                    public void onError(String reason) {
                        activeDownloadHandle = null;
                        resetDownloadButton();
                        new Alert(Alert.AlertType.ERROR,
                                "Download failed: " + reason,
                                ButtonType.OK).showAndWait();
                    }

                    @Override
                    public void onCancelled() {
                        activeDownloadHandle = null;
                        resetDownloadButton();
                    }
                });
    }

    private void resetDownloadButton() {
        downloadBtn.setText("Download");
        downloadBtn.setDisable(selectedExpert.get() == null);
        if (downloadProgress != null) {
            downloadProgress.setVisible(false);
        }
    }

    private void cancelActiveDownload() {
        ServiceHandle h = activeDownloadHandle;
        if (h != null) {
            h.cancel();
            activeDownloadHandle = null;
            resetDownloadButton();
        }
    }

    // ── Back button → return to workspace ────────────────────

    @FXML
    private void onBack() {
        cancelActiveDownload();
        Navigator.showWorkspace();
    }

    // ── Load local adapters ────────────────────

    private void loadLocalAdapters() throws IOException {
        // Remove the current local adapters
        allExperts.removeAll(
                allExperts.stream()
                        .filter(e -> e.getSource() == Expert.Source.LOCAL)
                        .toList()
        );

        allExperts.add(Expert.BASE_MODEL);

        // Reload local adapters
        Files.list(NetworkManager.getInstance().getAdaptersDir())
                .filter(Files::isDirectory)
                .forEach(
                        (Path dir) -> {
                            File child = dir.resolve("adapter.yml").toFile();
                            if (child.exists() && child.isFile()) {
                                AdapterMetadata metadata = AdapterMetadata.fromFile(child.toPath());
                                allExperts.add(
                                        new Expert(
                                                metadata.name(),
                                                metadata.domain(),
                                                Expert.Source.LOCAL,
                                                dir.getFileName().toString()
                                        )
                                );
                            };
                        }
                );
    }

    // ── Network discovery ────────────────────────────────────

    /**
     * Queries gossip state for remote adapters and adds them as "Remote"
     * experts to the list.
     */
    private void loadRemoteAdapters() {
        NetworkManager net = NetworkManager.getInstance();
        if (!net.isRunning()) return;

        Map<NodeId, String> peerAdapters = net.discoverAdapters();
        for (var entry : peerAdapters.entrySet()) {
            String listing = entry.getValue();
            if (listing == null || listing.isEmpty()) continue;

            for (String item : listing.split(",")) {
                if (item.isEmpty()) continue;
                int colon = item.lastIndexOf(':');
                String name = colon > 0 ? item.substring(0, colon) : item;
                allExperts.add(new Expert("(Unknown)", "(Unknown)", Expert.Source.REMOTE, name));
            }
        }
    }

    public void onAddAdapterFile(ActionEvent actionEvent) {
        Alert alert = new Alert(
                Alert.AlertType.INFORMATION,
                "To create a new adapter, ensure the directory selected only contains adapter files. " +
                        "Mosaic supports the following adapters\n\n" +
                        "  1. Safetensor (.safetensors) with adapter config (adapter_config.json).\n" +
                        "  2. .gguf file.\n\n" +
                        "with (optionally) any of the following files:\n\n" +
                        "  • system_prompt.txt — A system prompt applied automatically when this adapter is used.\n" +
                        "  • parameter_suggestions.json — Default generation parameters.\n\n" +
                        "For more details, please consult the documentation found in llm-server/SETUP.md.",
                new ButtonType("Understood")
        );
        alert.showAndWait();

        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Select Adapter Directory");

        File newAdapterDir = directoryChooser.showDialog(addAdapterBtn.getScene().getWindow());

        if (newAdapterDir == null) {
            return;
        }

        File targetDir = newAdapterTargetDir(newAdapterDir);

        try {
            FileUtils.copyDirectory(newAdapterDir, targetDir);
        } catch (IOException e) {
            new Alert(Alert.AlertType.ERROR, "Failed to copy adapter: " + e.getMessage()).showAndWait();
            return;
        }

        if (!targetDir.exists()) {
            new Alert(Alert.AlertType.ERROR, "Failed to copy adapter: File missing after copy").showAndWait();
            return;
        }

        Path configFile = targetDir.toPath().resolve("adapter.yml");
        if (!Files.exists(configFile)) {
            new AdapterMetadata("", "").toFile(
                    targetDir.toPath().resolve("adapter.yml")
            );
        }

        try {
            loadLocalAdapters();
        } catch (IOException e) {
            System.getLogger("ExpertSelectionController").log(
                    System.Logger.Level.ERROR,
                    "Loading local adapters failed: " + e.getMessage()
            );
            new Alert(
                    Alert.AlertType.ERROR,
                    "Loading local adapters failed (%s).".formatted(e.getMessage())
            ).showAndWait();
        }
    }

    private static File newAdapterTargetDir(File newAdapterDir) {
        Path adaptersDir = NetworkManager.getInstance().getAdaptersDir();
        String originalName = newAdapterDir.getName();
        File targetDir = adaptersDir.resolve(originalName).toFile();

        // Check for collisions
        if (targetDir.exists()) {
            int counter = 1;
            while (targetDir.exists()) {
                String newName = originalName + "-" + counter;
                targetDir = adaptersDir.resolve(newName).toFile();
                counter++;
            }
        }
        return targetDir;
    }
}
