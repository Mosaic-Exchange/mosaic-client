package com.mosaic.client.ui.screens.expert;

import com.mosaic.client.ConnectionMonitor;
import com.mosaic.client.Navigator;
import com.mosaic.client.RumorClient;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.*;

/**
 * Controller for the Expert Selection screen.
 *
 * APP-4 (#8) — Network-aware implementation
 *   AC1: Filters panel (domain / source / availability dropdowns) — populated dynamically
 *   AC2: ListView driven by live cluster data from ConnectionMonitor
 *   AC3: Selecting an expert updates the detail panel
 *   AC4: "Confirm" — disabled for Disconnected experts
 *   AC5: "Download" — calls RumorClient.startDownload(), streams progress bar
 */
public class ExpertSelectionController {

    // ── AC1: Filter controls ─────────────────────────────────
    @FXML private ComboBox<String> domainFilter;
    @FXML private ComboBox<String> sourceFilter;
    @FXML private ComboBox<String> availabilityFilter;

    // ── AC2: Expert list + state indicators ─────────────────
    @FXML private ListView<Expert>  expertListView;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Label             listPlaceholder;
    @FXML private VBox              errorSection;

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

    // ── Download progress ────────────────────────────────────
    @FXML private VBox        downloadProgressSection;
    @FXML private Label       downloadStatusLabel;
    @FXML private ProgressBar downloadProgressBar;

    private Expert selectedExpert;
    private final ObservableList<Expert> allExperts     = FXCollections.observableArrayList();
    private FilteredList<Expert>         filteredExperts;

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    @FXML
    public void initialize() {
        // StackPane children don't need managed bindings — we control them by visibility only.
        // But the download section is in a VBox so it needs to collapse properly.
        downloadProgressSection.managedProperty().bind(downloadProgressSection.visibleProperty());

        // Filters — domain is repopulated dynamically on each cluster update
        domainFilter.setItems(FXCollections.observableArrayList("All Domains"));
        domainFilter.setValue("All Domains");
        sourceFilter.setItems(FXCollections.observableArrayList("All Sources", "Local", "Remote"));
        sourceFilter.setValue("All Sources");
        availabilityFilter.setItems(FXCollections.observableArrayList("All", "Connected", "Disconnected"));
        availabilityFilter.setValue("All");
        domainFilter.setOnAction(e -> applyFilters());
        sourceFilter.setOnAction(e -> applyFilters());
        availabilityFilter.setOnAction(e -> applyFilters());

        // List binding
        filteredExperts = new FilteredList<>(allExperts, e -> true);
        expertListView.setItems(filteredExperts);
        expertListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Expert item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else setText(item.name() + "  ·  " + item.domain());
            }
        });
        expertListView.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldVal, newVal) -> onExpertSelected(newVal));

        // Start in LOADING state — will be replaced immediately below if data is available.
        showLoading();

        // Subscribe to live cluster data.
        ConnectionMonitor mon = Navigator.getConnectionMonitor();
        if (mon != null) {
            // onClusterChanged is already dispatched on the FX thread by ConnectionMonitor.
            mon.onClusterChanged(this::refreshFromCluster);
            mon.onConnectionStateChanged(state -> {
                if (state == ConnectionMonitor.State.DISCONNECTED) showError();
                else if (state == ConnectionMonitor.State.CONNECTING) showLoading();
                // CONNECTED / DEGRADED: onClusterChanged handles list updates.
            });

            // Seed the list immediately with whatever the monitor last saw.
            // Without this, opening the screen after a stable cluster never triggers
            // onClusterChanged (no change detected) and the spinner would spin forever.
            if (mon.getCurrentState() != ConnectionMonitor.State.CONNECTING) {
                refreshFromCluster(mon.getLastCluster());
            } else if (mon.getCurrentState() == ConnectionMonitor.State.DISCONNECTED) {
                showError();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cluster → Expert mapping
    // -------------------------------------------------------------------------

    private void refreshFromCluster(List<RumorClient.NodeInfo> cluster) {
        List<Expert> mapped = new ArrayList<>();
        for (RumorClient.NodeInfo node : cluster) {
            // Only expose nodes that run InferenceService.
            boolean hasInference = node.services().stream()
                .anyMatch(s -> s.contains("InferenceService"));
            if (!hasInference) continue;
            mapped.add(nodeToExpert(node));
        }

        allExperts.setAll(mapped);

        // Rebuild domain filter to reflect actual data.
        Set<String> domains = new LinkedHashSet<>();
        domains.add("All Domains");
        for (Expert e : mapped) domains.add(e.domain());
        String prevDomain = domainFilter.getValue();
        domainFilter.setItems(FXCollections.observableArrayList(new ArrayList<>(domains)));
        domainFilter.setValue(domains.contains(prevDomain) ? prevDomain : "All Domains");

        applyFilters();

        if (mapped.isEmpty()) showEmpty();
        else showList();
    }

    private static Expert nodeToExpert(RumorClient.NodeInfo node) {
        String source  = node.self() ? "Local" : "Remote";
        String status  = "ALIVE".equalsIgnoreCase(node.status()) ? "Connected" : "Disconnected";

        // Parse the first entry from SHARED_FILES gossip state as the adapter file.
        String adapterFile = "";
        String name        = node.id(); // nodeId fallback

        String sharedFiles = node.sharedFiles();
        if (sharedFiles != null && !sharedFiles.isEmpty()) {
            String firstEntry = sharedFiles.split(",")[0].trim();
            int colon = firstEntry.lastIndexOf(':');
            adapterFile = colon > 0 ? firstEntry.substring(0, colon) : firstEntry;
            // Use filename without extension as the display name.
            int dot = adapterFile.lastIndexOf('.');
            name = dot > 0 ? adapterFile.substring(0, dot) : adapterFile;
        }

        String domain = deriveDomain(adapterFile, source);
        return new Expert(name, domain, source, adapterFile, status);
    }

    /** Best-effort domain from adapter filename; first word-token capitalised. */
    private static String deriveDomain(String adapterFile, String fallback) {
        if (adapterFile == null || adapterFile.isEmpty()) return fallback;
        String base = adapterFile.toLowerCase(Locale.ROOT);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String first = base.split("[_\\-.]")[0];
        if (first.isEmpty()) return fallback;
        return Character.toUpperCase(first.charAt(0)) + first.substring(1);
    }

    // -------------------------------------------------------------------------
    // State display helpers
    // -------------------------------------------------------------------------

    private void showLoading() {
        loadingIndicator.setVisible(true);
        expertListView.setVisible(false);
        listPlaceholder.setVisible(false);
        errorSection.setVisible(false);
    }

    private void showList() {
        loadingIndicator.setVisible(false);
        expertListView.setVisible(true);
        listPlaceholder.setVisible(false);
        errorSection.setVisible(false);
    }

    private void showEmpty() {
        loadingIndicator.setVisible(false);
        expertListView.setVisible(false);
        listPlaceholder.setVisible(true);
        errorSection.setVisible(false);
    }

    private void showError() {
        loadingIndicator.setVisible(false);
        expertListView.setVisible(false);
        listPlaceholder.setVisible(false);
        errorSection.setVisible(true);
    }

    // -------------------------------------------------------------------------
    // AC3: Detail panel
    // -------------------------------------------------------------------------

    private void onExpertSelected(Expert expert) {
        selectedExpert = expert;
        if (expert == null) {
            detailName.setText("—"); detailDomain.setText("—");
            detailSource.setText("—"); detailAdapter.setText("—");
            detailStatus.setText("—");
            detailStatus.getStyleClass().removeAll("expert-status-connected", "expert-status-disconnected");
            confirmBtn.setDisable(true);
            downloadBtn.setDisable(true);
            return;
        }

        detailName.setText(expert.name());
        detailDomain.setText(expert.domain());
        detailSource.setText(expert.source());
        detailAdapter.setText(expert.adapterFile().isEmpty() ? "—" : expert.adapterFile());
        detailStatus.setText(expert.status());

        detailStatus.getStyleClass().removeAll("expert-status-connected", "expert-status-disconnected");
        boolean connected = "Connected".equals(expert.status());
        detailStatus.getStyleClass().add(connected ? "expert-status-connected" : "expert-status-disconnected");

        // Confirm disabled for disconnected experts.
        confirmBtn.setDisable(!connected);
        // Download available for Remote experts that have an adapter file to fetch.
        downloadBtn.setDisable(!"Remote".equals(expert.source()) || expert.adapterFile().isEmpty());
    }

    // -------------------------------------------------------------------------
    // AC1: Filter logic
    // -------------------------------------------------------------------------

    private void applyFilters() {
        String domain       = domainFilter.getValue();
        String source       = sourceFilter.getValue();
        String availability = availabilityFilter.getValue();

        filteredExperts.setPredicate(expert -> {
            if (domain != null && !"All Domains".equals(domain) && !expert.domain().equals(domain))
                return false;
            if (source != null && !"All Sources".equals(source) && !expert.source().equals(source))
                return false;
            if (availability != null && !"All".equals(availability) && !expert.status().equals(availability))
                return false;
            return true;
        });

        expertListView.getSelectionModel().clearSelection();
    }

    // -------------------------------------------------------------------------
    // AC4: Confirm
    // -------------------------------------------------------------------------

    @FXML
    private void onConfirm() {
        if (selectedExpert != null) {
            Navigator.setActiveExpert(selectedExpert.name(), selectedExpert.domain(),
                                      selectedExpert.source(), selectedExpert.adapterFile(),
                                      selectedExpert.status());
        }
        Navigator.showWorkspace();
    }

    // -------------------------------------------------------------------------
    // AC5: Download with progress
    // -------------------------------------------------------------------------

    @FXML
    private void onDownload() {
        if (selectedExpert == null || selectedExpert.adapterFile().isEmpty()) return;
        RumorClient client = Navigator.getRumorClient();
        if (client == null) return;

        String file = selectedExpert.adapterFile();
        downloadBtn.setDisable(true);
        downloadProgressSection.setVisible(true);
        downloadStatusLabel.setText("Starting download…");
        downloadProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        Thread.ofVirtual().start(() -> {
            // startDownload() blocks until the server acknowledges the request.
            try {
                client.startDownload(file);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    downloadStatusLabel.setText("Failed to start: " + e.getMessage());
                    downloadProgressBar.setProgress(0);
                    downloadBtn.setDisable(false);
                });
                return;
            }

            // subscribeDownloadProgress() starts its own virtual thread and returns immediately.
            // Callbacks arrive on that thread — always wrap with Platform.runLater().
            client.subscribeDownloadProgress(
                progress -> Platform.runLater(() -> {
                    double pct = progress.totalBytes() > 0
                        ? (double) progress.bytesReceived() / progress.totalBytes()
                        : ProgressBar.INDETERMINATE_PROGRESS;
                    downloadProgressBar.setProgress(pct);
                    downloadStatusLabel.setText(String.format("Downloading…  %s / %s",
                        humanBytes(progress.bytesReceived()), humanBytes(progress.totalBytes())));
                }),
                () -> Platform.runLater(this::onDownloadComplete),
                err -> Platform.runLater(() -> {
                    downloadStatusLabel.setText("Download failed: " + err);
                    downloadProgressBar.setProgress(0);
                    downloadBtn.setDisable(false);
                })
            );
        });
    }

    private void onDownloadComplete() {
        downloadProgressBar.setProgress(1.0);
        downloadStatusLabel.setText("Download complete");

        // Promote the expert's status to Connected in the list and detail panel.
        if (selectedExpert != null) {
            Expert updated = new Expert(selectedExpert.name(), selectedExpert.domain(),
                                        selectedExpert.source(), selectedExpert.adapterFile(),
                                        "Connected");
            int idx = allExperts.indexOf(selectedExpert);
            if (idx >= 0) allExperts.set(idx, updated);
            onExpertSelected(updated);
        }
    }

    // -------------------------------------------------------------------------
    // Retry / Back
    // -------------------------------------------------------------------------

    @FXML
    private void onRetry() {
        // Transition to LOADING state — ConnectionMonitor will fire onClusterChanged /
        // onConnectionStateChanged automatically if the server becomes reachable.
        showLoading();
    }

    @FXML
    private void onBack() {
        Navigator.showWorkspace();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String humanBytes(long bytes) {
        if (bytes <= 0)                    return "?";
        if (bytes < 1_024)                 return bytes + " B";
        if (bytes < 1_048_576)             return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824)         return String.format("%.1f MB", bytes / 1_048_576.0);
        return                                    String.format("%.2f GB", bytes / 1_073_741_824.0);
    }

    // ── Expert record ────────────────────────────────────────

    /**
     * Lightweight record representing a single inference-capable node.
     * All fields are derived from gossip state; domain is best-effort from the adapter filename.
     */
    record Expert(String name, String domain, String source,
                  String adapterFile, String status) {}
}
