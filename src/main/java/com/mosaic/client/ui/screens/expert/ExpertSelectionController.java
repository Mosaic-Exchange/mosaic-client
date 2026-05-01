package com.mosaic.client.ui.screens.expert;

import com.mosaic.client.Navigator;
import com.mosaic.client.db.dao.AdapterDao;
import com.mosaic.client.service.LLMServer;
import com.mosaic.client.service.NetworkManager;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import org.apache.commons.io.FileUtils;
import org.rumor.gossip.NodeId;
import org.rumor.service.ServiceHandle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

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
    @FXML private Button detailSaveBtn;
    @FXML private Button detailResetBtn;

    @FXML private ProgressBar downloadProgress;
    @FXML private Label overlayMessage;
    @FXML private BorderPane inputRoot;

    /** LLM server state */
    private final ReadOnlyObjectProperty<LLMServer.State> llmServerState = NetworkManager.getInstance().llmServerStateProperty();

    /** Currently selected expert. */
    private final ObjectProperty<Expert> selectedExpert = new SimpleObjectProperty<>();

    /** Master (unfiltered) list of experts. */
    private ObservableList<Expert> allExperts;
    
    /** Set of domains. */
    private ObservableSet<String> allDomains = FXCollections.observableSet();

    /** Adapter DAO */
    private final AdapterDao adapterDao = new AdapterDao();

    /** Filtered view that the ListView displays. */
    private FilteredList<Expert> filteredExperts;

    /** Handle for an in-progress adapter download (null when idle). */
    private volatile ServiceHandle activeDownloadHandle;
    /** True while a remote adapter download is active. */
    private final BooleanProperty downloadInProgress = new SimpleBooleanProperty(false);
    /** True while expert lists/filters are being bulk refreshed. */
    private boolean refreshingExperts;

    @FXML
    public void initialize() {
        // ── AC2: Local experts + network-discovered remote experts ─
        allExperts = FXCollections.observableArrayList();

        try {
            loadLocalAdapters();
        } catch (SQLException e) {
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

        // Publish the initial catalog so peers can see our loaded adapters immediately
        refreshNetworkAdapterCatalog();

        // Merge remote adapters discovered via gossip
        loadRemoteAdapters();

        // ── AC1: Populate filter dropdowns ───────────────────
        allDomains.addListener((SetChangeListener<String>) change -> updateDomainFilterItems());

        // Initialize allDomains with current experts
        allExperts.forEach(e -> {
            allDomains.add(e.getDomain());
            e.domainProperty().addListener((obs, oldV, newV) -> allDomains.add(newV));
        });

        // Listen for new experts being added
        allExperts.addListener((ListChangeListener<Expert>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    c.getAddedSubList().forEach(e -> {
                        allDomains.add(e.getDomain());
                        e.domainProperty().addListener((obs, oldV, newV) -> allDomains.add(newV));
                    });
                }
            }
        });

        updateDomainFilterItems();
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
                textProperty().unbind();
                if (empty || item == null) {
                    setText(null);
                } else {
                    textProperty().bind(item.nameProperty().concat("  ·  ").concat(item.domainProperty()));
                }
            }
        });

        // ── AC3: Selection listener updates detail panel ─────
        // Bind ListView selection to the selectedExpert property
        selectedExpert.bind(expertListView.getSelectionModel().selectedItemProperty());

        // Bind Detail Panel Labels using flatMap
        detailSource.textProperty().bind(selectedExpert.flatMap(Expert::sourceProperty).map(Object::toString).orElse("—"));
        detailAdapter.textProperty().bind(selectedExpert.flatMap(Expert::adapterFileProperty).orElse("—"));
        detailStatus.textProperty().bind(selectedExpert.flatMap(Expert::statusProperty).map(Object::toString).orElse("—"));

        // Change a few things based on selected expert
        selectedExpert.addListener((observable, oldValue, newValue) -> {
            if (newValue == null) {
                // Cannot use this button without an expert selected.
                detailSaveBtn.setVisible(false);
                detailName.setEditable(false);
                detailDomain.setEditable(false);
                detailName.setText("");
                detailDomain.setText("");
                return;
            }

            // Cannot use this button with the base model (though the text should still change).
            detailSaveBtn.setVisible(newValue != Expert.BASE_MODEL);
            detailName.setEditable(newValue != Expert.BASE_MODEL);
            detailDomain.setEditable(newValue != Expert.BASE_MODEL);

            detailName.setText(newValue.getName());
            detailDomain.setText(newValue.getDomain());

            updateDownloadButton(newValue);
        });

        // Selectively hide the save/reset buttons (reset button visibility bound to save button)
        detailName.textProperty().addListener((observable, oldValue, newValue) -> {
            updateDetailSaveButton(newValue, detailDomain.getText());
        });
        detailDomain.textProperty().addListener((observable, oldValue, newValue) -> {
            updateDetailSaveButton(detailName.getText(), newValue);
        });

        // Initial state
        downloadBtn.disableProperty().bind(
                selectedExpert.isNull()
                        .or(selectedExpert.isEqualTo(Expert.BASE_MODEL))
                        .or(downloadInProgress)
                        .or(downloadBtn.textProperty().isEqualTo("Load").and(llmServerState.isEqualTo((LLMServer.State) LLMServer.State.CONNECTED).not()))
                        .or(downloadBtn.textProperty().isEqualTo("Unload").and(llmServerState.isEqualTo((LLMServer.State) LLMServer.State.CONNECTED).not()))
        );
        detailSaveBtn.setVisible(false);
        detailResetBtn.visibleProperty().bind(detailSaveBtn.visibleProperty());

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
    }

    // ── AC1: Filter logic ────────────────────────────────────

    private void updateDomainFilterItems() {
        if (refreshingExperts) return;
        ObservableList<String> items = FXCollections.observableArrayList("All Domains");
        items.addAll(allDomains.stream().sorted().toList());
        domainFilter.setItems(items);
    }

    private void applyFilters() {
        if (refreshingExperts || filteredExperts == null) return;
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

        // Clear selection when filters change, so detail panel resets.
        // Skip this while backing data is mutating to avoid ListView index churn.
        if (expertListView != null && expertListView.getSelectionModel() != null) {
            expertListView.getSelectionModel().clearSelection();
        }
    }

    // ── AC4: Confirm → go to workspace ───────────────────────

    @FXML
    private void onConfirm() {
        Expert currentSelected = selectedExpert.get();
        if (currentSelected != null) {
            if (!currentSelected.isRemote() && !currentSelected.isLoaded()) {
                new Alert(
                        Alert.AlertType.ERROR,
                        "Local adapters must be loaded before they can be used."
                ).showAndWait();
                return;
            }
            Navigator.setActiveExpert(currentSelected);
            Navigator.showWorkspace();
        } else {
            Navigator.showWorkspace();
        }
    }

    // ── Helper ────────────────
    private void lockScreen(String message) {
        inputRoot.setDisable(true);
        overlayMessage.setText(message);
        overlayMessage.setDisable(false);
    }

    private void unlockScreen() {
        inputRoot.setDisable(false);
        overlayMessage.setDisable(true);
        overlayMessage.setText("");
    }

    // ── Multi-function "download" button ────────────────

    private void updateDownloadButton(Expert e) {
        if (e.isRemote()) {
            downloadBtn.setText("Download");
        } else if (e.isLoaded()) {
            downloadBtn.setText("Unload");
        } else {
            downloadBtn.setText("Load");
        }
    }

    @FXML
    private void onDownload() {
        // Determine course of action based on user intent (the button label value).
        switch (downloadBtn.textProperty().getValue()) {
            case "Download":
                download();
                return;
            case "Load":
                loadAdapter(selectedExpert.get());
                return;
            case "Unload":
                unloadFromLLMServer(selectedExpert.get());
                return;
            default:
                throw new IllegalStateException("Download button label was set to an illegal value.");
        }
    }

    private void download() {
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
        downloadInProgress.set(true);
        downloadBtn.setText("Downloading…");
        if (downloadProgress != null) {
            downloadProgress.setVisible(true);
            downloadProgress.setProgress(-1); // indeterminate
        }

        // Capture before the selection changes
        Expert remoteExpert = currentSelected;

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
                        // Auto-import: register with LLM server and add to local list
                        importDownloadedAdapter(remoteExpert, outputPath);
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

    /**
     * After a successful download, copies the GGUF into the adapters directory,
     * registers it with the LLM server, and adds a LOCAL expert entry to the list.
     * Removes the REMOTE entry that was just downloaded.
     */
    private void importDownloadedAdapter(Expert remoteExpert, Path ggufPath) {
        String fileName = ggufPath.getFileName().toString();
        String stem = fileName.endsWith(".gguf")
                ? fileName.substring(0, fileName.length() - 5)
                : fileName;

        // Preserve name/domain from catalog if available, otherwise fall back to stem
        String displayName = (remoteExpert.getName() == null
                || remoteExpert.getName().startsWith("("))
                ? stem : remoteExpert.getName();
        String domain = (remoteExpert.getDomain() == null
                || remoteExpert.getDomain().startsWith("("))
                ? "Unknown" : remoteExpert.getDomain();

        lockScreen("Registering downloaded adapter…");

        NetworkManager.getInstance().importAdapter(ggufPath, stem,
                response -> {
                    try {
                        // Remove the remote entry regardless of registration outcome
                        allExperts.removeIf(e -> e == remoteExpert);

                        if (response.error().isPresent()) {
                            // Registration failed — add as UNLOADED so the user can retry
                            Expert local = new Expert(displayName, domain, Expert.Source.LOCAL, stem);
                            try {
                                adapterDao.upsert(local);
                            } catch (SQLException e) {
                                System.getLogger("ExpertSelectionController").log(
                                        System.Logger.Level.ERROR, "DB upsert failed: " + e.getMessage());
                            }
                            allExperts.add(local);
                            new Alert(Alert.AlertType.WARNING,
                                    "Adapter downloaded but could not be loaded: " + response.error().get()
                                            + "\nYou can load it manually from the expert list.",
                                    ButtonType.OK).showAndWait();
                        } else {
                            // Registration succeeded — add as LOADED
                            Expert local = new Expert(displayName, domain, Expert.Source.LOCAL,
                                    stem, response.adapterId());
                            try {
                                adapterDao.upsert(local);
                            } catch (SQLException e) {
                                System.getLogger("ExpertSelectionController").log(
                                        System.Logger.Level.ERROR, "DB upsert failed: " + e.getMessage());
                            }
                            allExperts.add(local);
                            refreshNetworkAdapterCatalog();
                        }
                    } catch (Exception e) {
                        System.getLogger("ExpertSelectionController").log(
                                System.Logger.Level.ERROR, "Post-download UI update failed: " + e.getMessage());
                        new Alert(Alert.AlertType.ERROR,
                                "Adapter registration completed but UI update failed: " + e.getMessage(),
                                ButtonType.OK).showAndWait();
                    } finally {
                        unlockScreen();
                    }
                },
                throwable -> {
                    try {
                        allExperts.removeIf(e -> e == remoteExpert);
                        Expert local = new Expert(displayName, domain, Expert.Source.LOCAL, stem);
                        try {
                            adapterDao.upsert(local);
                        } catch (SQLException e) {
                            System.getLogger("ExpertSelectionController").log(
                                    System.Logger.Level.ERROR, "DB upsert failed: " + e.getMessage());
                        }
                        allExperts.add(local);
                        new Alert(Alert.AlertType.WARNING,
                                "Adapter downloaded but registration failed: " + throwable.getMessage()
                                        + "\nYou can load it manually from the expert list.",
                                ButtonType.OK).showAndWait();
                    } finally {
                        unlockScreen();
                    }
                });
    }

    private void resetDownloadButton() {
        downloadInProgress.set(false);
        downloadBtn.setText("Download");
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

    private void loadAdapter(Expert expert) {
        lockScreen("Loading adapter to LLM server...");

        NetworkManager.getInstance().registerAdapter(
                NetworkManager.getInstance().getAdaptersDir().resolve(expert.getAdapterFile()),
                response -> {
                    // Handle error responses
                    if (response.error().isPresent()) {
                        Platform.runLater(() -> {
                            new Alert(Alert.AlertType.ERROR,
                                    "Failed to register adapter: " + response.error().get()
                            ).showAndWait();
                        });
                        unlockScreen();
                        return;
                    }

                    expert.load(response.adapterId());

                    // Attempt database update
                    try {
                        adapterDao.upsert(expert);
                    } catch (SQLException e) {
                        // Failed, reset server side ID.
                        expert.unload();
                        Platform.runLater(() -> {
                            new Alert(
                                    Alert.AlertType.ERROR,
                                    "Failed to register adapter: Could not update server side ID in database (%s)."
                                            .formatted(e.getMessage())
                            ).showAndWait();
                        });
                        return;
                    } finally {
                        // Update button
                        updateDownloadButton(expert);

                        // Re-enable input
                        unlockScreen();
                    }

                    // Publish updated catalog so peers can use this adapter remotely
                    refreshNetworkAdapterCatalog();

                    // Success!
                    System.getLogger("ExpertSelectionController").log(System.Logger.Level.INFO,
                            "Registered adapter with response: " + response);
                },
                throwable -> {
                    unlockScreen();
                    Platform.runLater(() -> { new Alert(Alert.AlertType.ERROR, "Error during adapter registration: " + throwable.getMessage()).showAndWait(); });
                    expert.unload();
                }
        );
    }

    private void unloadFromLLMServer(Expert expert) {
        assert expert != null : "Cannot unload null expert.";
        lockScreen("Removing adapter...");

        // Attempt database update
        try {
            adapterDao.upsert(
                    expert.getAdapterFile(),
                    expert.getName(),
                    expert.getDomain(),
                    null
            );
        } catch (SQLException e) {
            // Failed
            new Alert(
                    Alert.AlertType.ERROR,
                    ("Could not remove server side ID in database (%s). The operation will not proceed, as errors could " +
                            "result on restart.")
                            .formatted(e.getMessage())
            ).showAndWait();
            unlockScreen();
            return;
        }

        String serverSideId = expert.getServerSideId();
        expert.unload();

        NetworkManager.getInstance().deregisterAdapter(
                serverSideId,
                unused -> {
                    // Update button
                    updateDownloadButton(expert);

                    // Publish updated catalog — this adapter is no longer available remotely
                    refreshNetworkAdapterCatalog();

                    // Re-enable input
                    unlockScreen();

                    // Success!
                    System.getLogger("ExpertSelectionController").log(System.Logger.Level.INFO,
                            "Deregistered adapter with id: " + serverSideId
                    );
                },
                exception -> {
                    System.getLogger("ExpertSelectionController").log(System.Logger.Level.ERROR,
                            "Deregistering adapter with id '%s' failed (%s).".formatted(
                                    serverSideId,
                                    exception.getMessage()
                            )
                    );

                    Platform.runLater(() -> {
                            new Alert(
                                    Alert.AlertType.WARNING,
                                    ("Removing the adapter from the server failed (%s). It is not accessible, but " +
                                            "it will still take up storage space until removed manually.")
                                            .formatted(exception.getMessage())
                            ).showAndWait();
                    });

                    unlockScreen();
                }
        );
    }

    // ── Back button → return to workspace ────────────────────

    @FXML
    private void onBack() {
        cancelActiveDownload();
        Navigator.showWorkspace();
    }

    // ── Detail save ────────────────────
    private void updateDetailSaveButton(String name, String domain) {
        Expert expert = selectedExpert.get();
        if (expert == null) {
            detailSaveBtn.setVisible(false);
            return;
        }

        if (expert.getName().equals(name) && expert.getDomain().equals(domain)) {
            detailSaveBtn.setVisible(false);
            return;
        }
        detailSaveBtn.setVisible(true);

        // Disable saving if a name or domain is empty
        detailSaveBtn.setDisable(name.isEmpty() || domain.isEmpty());
    }

    public void onDetailReset(ActionEvent unused) {
        Expert expert = selectedExpert.get();
        if (expert != null) {
            detailName.setText(expert.getName());
            detailDomain.setText(expert.getDomain());
        }
    }

    public void onDetailSave(ActionEvent unused) {
        Expert expert = selectedExpert.get();
        if (expert == null) {
            return;
        }

        try {
            adapterDao.upsert(
                    expert.getAdapterFile(),
                    detailName.getText(),
                    detailDomain.getText(),
                    expert.getServerSideId()
            );
        } catch (SQLException e) {
            new Alert(Alert.AlertType.ERROR, "Failed to update adapter database: " + e.getMessage()).showAndWait();
            onDetailReset(null);
            return;
        }

        // DB operation succeeded
        expert.setName(detailName.getText());
        expert.setDomain(detailDomain.getText());

        updateDetailSaveButton(expert.getName(), expert.getDomain());

        // If this adapter is loaded, update catalog so peers see the new name/domain
        if (expert.isLoaded()) {
            refreshNetworkAdapterCatalog();
        }
    }

    // ── Load local adapters ────────────────────

    private void loadLocalAdapters() throws SQLException {
        refreshingExperts = true;
        try {
            // Build next list first, then replace in one shot. This avoids transient
            // invalid indices in FilteredList/ListView while listeners are firing.
            List<Expert> nextExperts = new ArrayList<>();
            nextExperts.add(Expert.BASE_MODEL);
            nextExperts.addAll(adapterDao.findAll());

            for (Expert existing : allExperts) {
                if (existing.getSource() == Expert.Source.REMOTE) {
                    nextExperts.add(existing);
                }
            }

            allExperts.setAll(nextExperts);
        } finally {
            refreshingExperts = false;
        }

        updateDomainFilterItems();
        applyFilters();
    }

    // ── Network discovery ────────────────────────────────────

    /**
     * Queries gossip state for remote adapters and merges them into the expert list.
     * Enriches REMOTE entries with name/domain/serverSideId from the CATALOG key when
     * available, enabling both download (Part A) and targeted remote inference (Part B).
     */
    private void loadRemoteAdapters() {
        // Remove stale remote entries before refreshing
        allExperts.removeIf(e -> e.getSource() == Expert.Source.REMOTE);

        NetworkManager net = NetworkManager.getInstance();
        if (!net.isRunning()) return;

        // ADAPTERS: nodeId -> "file.gguf:size,..."  (what's available for download)
        Map<NodeId, String> peerAdapters = net.discoverAdapters();
        // CATALOG:  nodeId -> "file.gguf|name|domain|serverId,..."  (what's loaded for remote inference)
        Map<NodeId, String> peerCatalogs = net.discoverAdapterCatalog();

        // Build a lookup from gguf filename -> catalog metadata
        record CatalogEntry(String name, String domain, String serverId, NodeId nodeId) {}
        Map<String, CatalogEntry> catalogByFile = new HashMap<>();
        for (var entry : peerCatalogs.entrySet()) {
            NodeId nodeId = entry.getKey();
            if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
            for (String item : entry.getValue().split(",")) {
                if (item.isEmpty()) continue;
                String[] parts = item.split("\\|", 4);
                if (parts.length == 4) {
                    catalogByFile.put(parts[0], new CatalogEntry(parts[1], parts[2], parts[3], nodeId));
                }
            }
        }

        // Add remote experts from ADAPTERS, enriched with catalog data where available
        for (var entry : peerAdapters.entrySet()) {
            NodeId nodeId = entry.getKey();
            String listing = entry.getValue();
            if (listing == null || listing.isEmpty()) continue;

            for (String item : listing.split(",")) {
                if (item.isEmpty()) continue;
                int colon = item.lastIndexOf(':');
                String adapterFile = colon > 0 ? item.substring(0, colon) : item;

                CatalogEntry cat = catalogByFile.get(adapterFile);
                String name   = (cat != null) ? cat.name()   : adapterFile;
                String domain = (cat != null) ? cat.domain() : "(Unknown)";

                Expert expert = new Expert(name, domain, Expert.Source.REMOTE, adapterFile);
                // Store remote targeting info so the workspace can do directed inference
                expert.setRemoteNodeId((cat != null) ? cat.nodeId() : nodeId);
                if (cat != null) {
                    expert.setRemoteAdapterId(cat.serverId());
                }
                allExperts.add(expert);
            }
        }
    }

    /**
     * Rebuilds the adapter catalog string from the current in-memory expert list
     * and pushes it to NetworkManager so it is published via gossip.
     * Call this after any load/unload/import operation.
     */
    private void refreshNetworkAdapterCatalog() {
        StringJoiner joiner = new StringJoiner(",");
        for (Expert e : allExperts) {
            if (e.getSource() == Expert.Source.LOCAL
                    && e.getServerSideId() != null
                    && !e.getServerSideId().isEmpty()) {
                joiner.add(e.getAdapterFile() + ".gguf|"
                         + e.getName()        + "|"
                         + e.getDomain()      + "|"
                         + e.getServerSideId());
            }
        }
        NetworkManager.getInstance().updateAdapterCatalog(joiner.toString());
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

        Expert newExpert = new Expert(newAdapterDir.getName(), "New adapter domain", Expert.Source.LOCAL, newAdapterDir.getName());
        try {
            adapterDao.upsert(newExpert);
        } catch (SQLException e) {
            new Alert(Alert.AlertType.ERROR, "Failed to update adapter database: " + e.getMessage()).showAndWait();
            return;
        }

        try {
            loadLocalAdapters();
        } catch (SQLException e) {
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
