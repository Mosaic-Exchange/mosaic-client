package com.mosaic.client.ui.screens.expert;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;

import com.mosaic.client.Navigator;
import com.mosaic.client.service.NetworkManager;

import org.rumor.gossip.NodeId;
import org.rumor.service.ServiceHandle;

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
    @FXML private VBox   detailCard;
    @FXML private Label  detailName;
    @FXML private Label  detailDomain;
    @FXML private Label  detailSource;
    @FXML private Label  detailAdapter;
    @FXML private Label  detailStatus;

    // ── AC4 + AC5: Action buttons ────────────────────────────
    @FXML private Button confirmBtn;
    @FXML private Button downloadBtn;

    @FXML private ProgressBar downloadProgress;

    /** Currently selected expert. */
    private Expert selectedExpert;

    /** Master (unfiltered) list of experts. */
    private ObservableList<Expert> allExperts;

    /** Filtered view that the ListView displays. */
    private FilteredList<Expert> filteredExperts;

    /** Handle for an in-progress adapter download (null when idle). */
    private volatile ServiceHandle activeDownloadHandle;

    @FXML
    public void initialize() {
        // ── AC2: Local experts + network-discovered remote experts ─
        allExperts = FXCollections.observableArrayList(
            new Expert("Gardening Expert",  "Gardening", "Local",  "gardening_expert.gguf",  "Connected"),
            new Expert("Chess Expert",      "Chess",     "Local",  "chess_expert.gguf",      "Connected")
        );

        // Merge remote adapters discovered via gossip
        loadRemoteAdapters();

        // ── AC1: Populate filter dropdowns ───────────────────
        domainFilter.setItems(FXCollections.observableArrayList(
                "All Domains", "Gardening", "Chess", "Remote"));
        domainFilter.setValue("All Domains");

        sourceFilter.setItems(FXCollections.observableArrayList(
                "All Sources", "Local", "Remote"));
        sourceFilter.setValue("All Sources");

        availabilityFilter.setItems(FXCollections.observableArrayList(
                "All", "Connected", "Disconnected"));
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
                    setText(item.name() + "  ·  " + item.domain());
                }
            }
        });

        // ── AC3: Selection listener updates detail panel ─────
        expertListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> onExpertSelected(newVal));
    }

    // ── AC3: Update detail panel ─────────────────────────────

    private void onExpertSelected(Expert expert) {
        selectedExpert = expert;
        if (expert == null) {
            detailName.setText("—");
            detailDomain.setText("—");
            detailSource.setText("—");
            detailAdapter.setText("—");
            detailStatus.setText("—");
            detailStatus.getStyleClass().removeAll("expert-status-connected", "expert-status-disconnected");
            confirmBtn.setDisable(true);
            downloadBtn.setDisable(true);
            return;
        }

        detailName.setText(expert.name());
        detailDomain.setText(expert.domain());
        detailSource.setText(expert.source());
        detailAdapter.setText(expert.adapterFile());
        detailStatus.setText(expert.status());

        // Style the status label: keep base 'label-body', only toggle status classes
        detailStatus.getStyleClass().removeAll(
                "expert-status-connected", "expert-status-disconnected");
        if ("Connected".equals(expert.status())) {
            detailStatus.getStyleClass().add("expert-status-connected");
        } else {
            detailStatus.getStyleClass().add("expert-status-disconnected");
        }

        confirmBtn.setDisable(false);
        downloadBtn.setDisable(false);
    }

    // ── AC1: Filter logic ────────────────────────────────────

    private void applyFilters() {
        String domain = domainFilter.getValue();
        String source = sourceFilter.getValue();
        String availability = availabilityFilter.getValue();

        filteredExperts.setPredicate(expert -> {
            if (domain != null && !"All Domains".equals(domain)
                    && !expert.domain().equals(domain)) {
                return false;
            }
            if (source != null && !"All Sources".equals(source)
                    && !expert.source().equals(source)) {
                return false;
            }
            if (availability != null && !"All".equals(availability)
                    && !expert.status().equals(availability)) {
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
        if (selectedExpert != null) {
            Navigator.setActiveExpert(
                    selectedExpert.name(),
                    selectedExpert.domain(),
                    selectedExpert.source(),
                    selectedExpert.adapterFile(),
                    selectedExpert.status());
        }
        Navigator.showWorkspace();
    }

    // ── AC5: Download adapter from remote peer ────────────────

    @FXML
    private void onDownload() {
        if (selectedExpert == null || !"Remote".equals(selectedExpert.source())) {
            new Alert(Alert.AlertType.INFORMATION, "Select a remote expert to download.",
                    ButtonType.OK).showAndWait();
            return;
        }
        if (activeDownloadHandle != null) {
            new Alert(Alert.AlertType.WARNING, "A download is already in progress.",
                    ButtonType.OK).showAndWait();
            return;
        }

        String adapterName = selectedExpert.adapterFile();
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
        downloadBtn.setDisable(selectedExpert == null);
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
                // Derive a display name from the filename
                String displayName = name.replace('_', ' ')
                        .replaceAll("\\.[^.]+$", ""); // strip extension
                allExperts.add(new Expert(displayName, "Remote", "Remote", name, "Connected"));
            }
        }
    }

    // ── Inner record for expert data ─────────────────────────

    /**
     * Lightweight record representing an expert entry.
     * Fields mirror the Local_Adapters schema + runtime status.
     */
    record Expert(String name, String domain, String source,
                  String adapterFile, String status) {
    }
}
