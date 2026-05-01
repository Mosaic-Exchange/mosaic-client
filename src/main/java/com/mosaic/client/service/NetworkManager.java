package com.mosaic.client.service;

import com.mosaic.client.db.DatabaseManager;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Task;
import org.rumor.gossip.EndpointState;
import org.rumor.gossip.NodeId;
import org.rumor.node.NodeType;
import org.rumor.node.Rumor;
import org.rumor.node.RumorConfig;
import org.rumor.service.DistributedService;
import org.rumor.service.RequestEvent;
import org.rumor.service.ServiceHandle;

import javafx.application.Platform;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Consumer;

/**
 * Singleton managing the Rumor network node and all distributed services.
 *
 * <p>All public methods that touch the UI use {@link Platform#runLater} to
 * marshal callbacks onto the JavaFX Application Thread, so controllers can
 * call them safely from any thread.
 */
public class NetworkManager {
    private static final String LOG_NAME = "rumor.log";

    private static NetworkManager instance;

    private Rumor rumor;
    private LLMServer llmServer;
    private InferenceService inferenceService;
    private AdapterTransferService adapterTransferService;
    private volatile boolean running;
    private Path adaptersDir;
    private Path adaptersGgufDir;

    /**
     * Catalog string published to gossip: comma-separated entries of
     * {@code "file.gguf|name|domain|serverSideId"} for loaded adapters.
     * Written on the FX thread; read by the gossip scheduler thread (safe via volatile).
     */
    private volatile String adapterCatalogCache = "";

    // JavaFX Properties
    private final ObjectProperty<LLMServer.State> llmServerState = new SimpleObjectProperty<>(LLMServer.State.DISCONNECTED);

    // Accessor renders the property read-only
    public ReadOnlyObjectProperty<LLMServer.State> llmServerStateProperty() { return llmServerState; }
    public LLMServer.State llmServerState() { return llmServerState.get(); }

    private NetworkManager() {}

    public static synchronized NetworkManager getInstance() {
        if (instance == null) {
            instance = new NetworkManager();
        }
        return instance;
    }

    /**
     * Starts the Rumor node with the given configuration.
     *
     * @param port          listen port
     * @param llmServerPort local LLM server port
     * @param nodeType      node type string ("master", "basic", "seed", "eviction")
     * @param debugEnabled  whether to write periodic debug snapshots
     * @param mosaicDir     root data directory; adapters are stored in subdirectories
     * @param logDir        directory for log files (e.g. "logs")
     * @param seeds         seed addresses as "host:port" strings; may be empty
     */
    public void start(String host, int port, int llmServerPort, String nodeType, boolean debugEnabled,
                      Path mosaicDir, Path logDir, String... seeds) throws Exception {
        if (running) return;

        adaptersDir     = mosaicDir.resolve("adapters");
        adaptersGgufDir = mosaicDir.resolve("adapters_gguf");
        Files.createDirectories(adaptersDir);
        Files.createDirectories(adaptersGgufDir);
        Files.createDirectories(logDir);

        // Seed the catalog cache from DB so it's published before the UI is shown
        adapterCatalogCache = buildCatalogFromDb();

        RumorConfig config = new RumorConfig();
        config.host(host).port(port).nodeType(NodeType.fromString(nodeType));

        for (String seed : seeds) {
            if (seed == null || seed.isBlank()) continue;
            String[] parts = seed.split(":");
            config.addSeed(parts[0].trim(), Integer.parseInt(parts[1].trim()));
        }

        llmServer = LLMServer.getInstance();
        llmServer.start("localhost", llmServerPort, null, logDir);
        llmServerState.bind(llmServer.stateProperty());

        rumor = new Rumor(config);

        inferenceService     = new InferenceService();
        adapterTransferService = new AdapterTransferService(adaptersGgufDir, () -> adapterCatalogCache);

        rumor.register(inferenceService, new DistributedService.Config()
                .remoteThreads(2)
                .remoteQueueCapacity(1)
                .localThreads(2)
                .localQueueCapacity(2));

        rumor.register(adapterTransferService, new DistributedService.Config()
                .remoteThreads(2)
                .remoteQueueCapacity(1));

        if (debugEnabled) {
            rumor.registerDebug(logDir.resolve(LOG_NAME));
        }

        rumor.start();
        running = true;
    }

    /**
     * Convenience overload for callers that don't need debug (e.g. Settings restart).
     */
    public void start(String host, int port, int llmServerPort, String nodeType, Path mosaicDir, Path logDir, String... seeds) throws Exception {
        start(host, port, llmServerPort, nodeType, false, mosaicDir, logDir, seeds);
    }

    /**
     * Stops the Rumor node and releases resources.
     */
    public void stop() {
        if (!running) return;
        running = false;
        if (rumor != null) {
            rumor.stop();
            rumor = null;
        }
        if (llmServer != null) {
            llmServer.stop();
            llmServer = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public NodeId localId() {
        return rumor != null ? rumor.localId() : null;
    }

    public Map<NodeId, EndpointState> getClusterState() {
        return rumor != null ? rumor.getClusterState() : Map.of();
    }

    // -- Inference --

    /**
     * Callback for streaming inference results back to the UI.
     */
    public interface InferenceCallback {
        void onToken(String token);
        void onComplete();
        void onError(String reason);
        void onCancelled();
    }

    /**
     * Runs inference locally on this node.
     *
     * @return a handle to cancel the request, or null if the network is not running
     */
    public ServiceHandle inferLocal(String prompt, String adapterId, InferenceCallback callback) {
        if (!running) {
            Platform.runLater(() -> callback.onError("Network not started"));
            return null;
        }

        InferenceRequest request = new InferenceRequest(prompt, adapterId);
        return inferenceService.request(request, event -> {
            switch (event) {
                case RequestEvent.StreamData d ->
                    Platform.runLater(() -> callback.onToken(
                            new String(d.raw(), StandardCharsets.UTF_8)));
                case RequestEvent.Succeeded s ->
                    Platform.runLater(callback::onComplete);
                case RequestEvent.Failed f ->
                    Platform.runLater(() -> callback.onError(f.reason()));
                case RequestEvent.Cancelled c ->
                    Platform.runLater(callback::onCancelled);
                default -> {}
            }
        });
    }

    /**
     * Dispatches inference to a remote peer (any peer offering InferenceService).
     *
     * @return a handle to cancel the request, or null if the network is not running
     */
    public ServiceHandle inferRemote(String prompt, InferenceCallback callback) {
        if (!running) {
            Platform.runLater(() -> callback.onError("Network not started"));
            return null;
        }

        InferenceRequest request = new InferenceRequest(prompt);
        return inferenceService.dispatch(request, event -> {
            switch (event) {
                case RequestEvent.StreamData d ->
                    Platform.runLater(() -> callback.onToken(
                            new String(d.raw(), StandardCharsets.UTF_8)));
                case RequestEvent.Succeeded s ->
                    Platform.runLater(callback::onComplete);
                case RequestEvent.Failed f ->
                    Platform.runLater(() -> callback.onError(f.reason()));
                case RequestEvent.Cancelled c ->
                    Platform.runLater(callback::onCancelled);
                default -> {}
            }
        });
    }

    /**
     * Dispatches inference to a specific remote node, including an adapter ID.
     * Used for remote inference with a known adapter on a known peer (Part B).
     *
     * @param adapterId  the server-side adapter ID on {@code targetNode}
     * @param targetNode the specific peer that owns the adapter
     * @return a handle to cancel the request, or null if the network is not running
     */
    public ServiceHandle inferRemote(String prompt, String adapterId, NodeId targetNode, InferenceCallback callback) {
        if (!running) {
            Platform.runLater(() -> callback.onError("Network not started"));
            return null;
        }

        InferenceRequest request = new InferenceRequest(prompt, adapterId);
        return inferenceService.dispatchToNode(request, targetNode, event -> {
            switch (event) {
                case RequestEvent.StreamData d ->
                    Platform.runLater(() -> callback.onToken(
                            new String(d.raw(), StandardCharsets.UTF_8)));
                case RequestEvent.Succeeded s ->
                    Platform.runLater(callback::onComplete);
                case RequestEvent.Failed f ->
                    Platform.runLater(() -> callback.onError(f.reason()));
                case RequestEvent.Cancelled c ->
                    Platform.runLater(callback::onCancelled);
                default -> {}
            }
        });
    }

    // -- Adapter transfer --

    /**
     * Callback for adapter download progress.
     */
    public interface AdapterDownloadCallback {
        void onProgress(long bytesReceived);
        void onComplete(Path outputPath);
        void onError(String reason);
        void onCancelled();
    }

    /**
     * Returns remote peers' adapter listings (ADAPTERS gossip key) from gossip state.
     */
    public Map<NodeId, String> discoverAdapters() {
        if (!running) return Map.of();
        return adapterTransferService.discoverAdapters();
    }

    /**
     * Returns remote peers' adapter catalog (CATALOG gossip key) from gossip state.
     * Entries contain display name, domain, and server-side ID for loaded adapters.
     */
    public Map<NodeId, String> discoverAdapterCatalog() {
        if (!running) return Map.of();
        return adapterTransferService.discoverAdapterCatalog();
    }

    /**
     * Downloads an adapter GGUF file from a remote peer into the local
     * {@code adapters_gguf} directory. On cancel or failure the partial file is deleted.
     *
     * @return a handle to cancel the download, or null if not running
     */
    public ServiceHandle downloadAdapter(String adapterName, AdapterDownloadCallback callback) {
        if (!running) {
            Platform.runLater(() -> callback.onError("Network not started"));
            return null;
        }

        Path outputPath = adaptersGgufDir.resolve(adapterName).toAbsolutePath().normalize();

        // Path traversal guard
        if (!outputPath.startsWith(adaptersGgufDir)) {
            Platform.runLater(() -> callback.onError("Invalid adapter name"));
            return null;
        }

        // Use a .part suffix during download so incomplete files are obvious
        Path partPath = outputPath.resolveSibling(adapterName + ".part");
        final long[] bytesReceived = {0};

        try {
            Files.createDirectories(outputPath.getParent());
            FileOutputStream fos = new FileOutputStream(partPath.toFile());

            ServiceHandle handle = adapterTransferService.downloadAdapter(adapterName, event -> {
                switch (event) {
                    case RequestEvent.StreamData d -> {
                        try {
                            byte[] chunk = d.raw();
                            fos.write(chunk);
                            bytesReceived[0] += chunk.length;
                            long total = bytesReceived[0];
                            Platform.runLater(() -> callback.onProgress(total));
                        } catch (IOException e) {
                            Platform.runLater(() -> callback.onError("Write error: " + e.getMessage()));
                        }
                    }
                    case RequestEvent.Succeeded s -> {
                        try {
                            fos.close();
                            Files.move(partPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
                            Platform.runLater(() -> callback.onComplete(outputPath));
                        } catch (IOException e) {
                            cleanupQuietly(partPath);
                            Platform.runLater(() -> callback.onError("Failed to finalize: " + e.getMessage()));
                        }
                    }
                    case RequestEvent.Failed f -> {
                        closeQuietly(fos);
                        cleanupQuietly(partPath);
                        Platform.runLater(() -> callback.onError(f.reason()));
                    }
                    case RequestEvent.Cancelled c -> {
                        closeQuietly(fos);
                        cleanupQuietly(partPath);
                        Platform.runLater(callback::onCancelled);
                    }
                    default -> {}
                }
            });

            return handle;
        } catch (IOException e) {
            cleanupQuietly(partPath);
            Platform.runLater(() -> callback.onError("Could not open output file: " + e.getMessage()));
            return null;
        }
    }

    public Path getAdaptersDir() {
        return adaptersDir;
    }

    public Path getAdaptersGgufDir() {
        return adaptersGgufDir;
    }

    /**
     * Registers a new adapter by copying its directory to the internal adapters folder
     * and notifying the LLM server. After successful registration the resulting GGUF
     * is also exported to the shared {@code adapters_gguf} directory so peers can download it.
     */
    public void registerAdapter(Path newAdapterDir, Consumer<LLMServer.AddAdapterResponse> onComplete, Consumer<Throwable> onFailure) {
        if (llmServer == null) {
            if (onFailure != null) {
                Platform.runLater(() -> onFailure.accept(new IllegalStateException("LLM Server not started")));
            }
            return;
        }
        llmServer.registerAdapter(newAdapterDir, response -> {
            if (response.error().isEmpty() && response.adapterFilename() != null) {
                exportGgufForSharing(response.adapterFilename());
            }
            if (onComplete != null) onComplete.accept(response);
        }, onFailure);
    }

    /**
     * Imports a downloaded GGUF file: copies it into a proper adapter directory,
     * registers it with the LLM server, and calls back with the result.
     *
     * <p>The GGUF file must already be present at {@code ggufPath} (typically inside
     * {@code adapters_gguf/}). A matching directory is created under {@code adapters/}
     * for use by load/unload operations.
     *
     * @param ggufPath path to the downloaded {@code .gguf} file
     * @param stem     base name without extension (becomes the adapter directory name)
     */
    public void importAdapter(Path ggufPath, String stem,
                              Consumer<LLMServer.AddAdapterResponse> onComplete,
                              Consumer<Throwable> onFailure) {
        if (llmServer == null) {
            if (onFailure != null) {
                Platform.runLater(() -> onFailure.accept(new IllegalStateException("LLM Server not started")));
            }
            return;
        }

        Task<Void> setupTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                Path adapterDir = adaptersDir.resolve(stem);
                Files.createDirectories(adapterDir);
                Files.copy(ggufPath, adapterDir.resolve(stem + ".gguf"), StandardCopyOption.REPLACE_EXISTING);
                return null;
            }
        };

        setupTask.setOnSucceeded(event -> {
            Path adapterDir = adaptersDir.resolve(stem);
            // Call LLM server directly — no need to re-export GGUF, it's already in adapters_gguf/
            llmServer.registerAdapter(adapterDir, onComplete, onFailure);
        });

        setupTask.setOnFailed(event -> {
            if (onFailure != null) {
                Platform.runLater(() -> onFailure.accept(setupTask.getException()));
            }
        });

        new Thread(setupTask).start();
    }

    /**
     * Deregister an adapter. Delegates to the LLMServer class.
     */
    public void deregisterAdapter(String serverSideId, Consumer<Void> onComplete, Consumer<Throwable> onFailure) {
        if (llmServer == null) {
            if (onFailure != null) {
                Platform.runLater(() -> onFailure.accept(new IllegalStateException("LLM Server not started")));
            }
            return;
        }
        llmServer.deregisterAdapter(serverSideId, onComplete, onFailure);
    }

    /**
     * Updates the adapter catalog string published to gossip peers.
     * Call this whenever an adapter is loaded, unloaded, or imported.
     *
     * <p>Format: comma-separated {@code "file.gguf|name|domain|serverSideId"} entries,
     * one per currently loaded adapter.
     */
    public void updateAdapterCatalog(String catalog) {
        this.adapterCatalogCache = catalog;
    }

    // -- Utilities --

    /**
     * After LLM server registration, copy the resulting GGUF into {@code adapters_gguf/}
     * so peers can download it via the gossip/transfer protocol.
     *
     * @param adapterFilename relative path returned by the LLM server (e.g. {@code "myAdapter/myAdapter.gguf"})
     */
    private void exportGgufForSharing(String adapterFilename) {
        // Normalise path separators (middleware may return OS-specific separators)
        Path rel  = Path.of(adapterFilename.replace('\\', '/'));
        String stem = rel.getName(0).toString();
        Path ggufSrc = Path.of("llm-server", "adapters").resolve(rel);
        Path ggufDst = adaptersGgufDir.resolve(stem + ".gguf");
        try {
            if (Files.exists(ggufSrc)) {
                Files.copy(ggufSrc, ggufDst, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.getLogger("NetworkManager").log(System.Logger.Level.WARNING,
                    "Could not export GGUF for sharing: " + e.getMessage());
        }
    }

    /**
     * Builds the initial catalog cache from the database at startup.
     * Only includes adapters that have a non-null, non-empty server_side_id
     * (i.e. those that were registered with the LLM server in a previous session).
     */
    private static String buildCatalogFromDb() {
        Connection conn = DatabaseManager.getInstance().getConnection();
        if (conn == null) return "";
        StringJoiner joiner = new StringJoiner(",");
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT file_path, name, domain, server_side_id FROM Local_Adapters " +
                     "WHERE server_side_id IS NOT NULL AND TRIM(server_side_id) != ''")) {
            while (rs.next()) {
                joiner.add(rs.getString("file_path") + ".gguf|"
                         + rs.getString("name")        + "|"
                         + rs.getString("domain")      + "|"
                         + rs.getString("server_side_id"));
            }
        } catch (SQLException e) {
            System.getLogger("NetworkManager").log(System.Logger.Level.WARNING,
                    "Could not read adapter catalog from DB: " + e.getMessage());
        }
        return joiner.toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        try { c.close(); } catch (IOException ignored) {}
    }

    private static void cleanupQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
    }
}
