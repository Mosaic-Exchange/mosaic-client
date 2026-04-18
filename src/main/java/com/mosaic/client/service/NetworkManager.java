package com.mosaic.client.service;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
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
import java.util.Map;

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
     * @param mosaicDir     root data directory; adapters are stored in a subdirectory
     * @param logDir        directory for log files (e.g. "logs")
     * @param seeds         seed addresses as "host:port" strings; may be empty
     */
    public void start(int port, int llmServerPort, String nodeType, boolean debugEnabled,
                      Path mosaicDir, Path logDir, String... seeds) throws Exception {
        if (running) return;

        adaptersDir = mosaicDir.resolve("adapters");
        Files.createDirectories(adaptersDir);
        Files.createDirectories(logDir);

        RumorConfig config = new RumorConfig();
        config.port(port).nodeType(NodeType.fromString(nodeType));

        for (String seed : seeds) {
            if (seed == null || seed.isBlank()) continue;
            String[] parts = seed.split(":");
            config.addSeed(parts[0].trim(), Integer.parseInt(parts[1].trim()));
        }

        llmServer = LLMServer.getInstance();
        llmServer.start("localhost", llmServerPort, null, logDir);
        llmServerState.bind(llmServer.stateProperty());

        rumor = new Rumor(config);

        inferenceService = new InferenceService();
        adapterTransferService = new AdapterTransferService(adaptersDir);

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
    public void start(int port, int llmServerPort, String nodeType, Path mosaicDir, Path logDir, String... seeds) throws Exception {
        start(port, llmServerPort, nodeType, false, mosaicDir, logDir, seeds);
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
    public ServiceHandle inferLocal(String prompt, InferenceCallback callback) {
        if (!running) {
            Platform.runLater(() -> callback.onError("Network not started"));
            return null;
        }

        InferenceRequest request = new InferenceRequest(prompt);
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
     * Dispatches inference to a remote peer.
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
     * Returns remote peers' adapter listings from gossip state.
     */
    public Map<NodeId, String> discoverAdapters() {
        if (!running) return Map.of();
        return adapterTransferService.discoverAdapters();
    }

    /**
     * Downloads an adapter from a remote peer. Writes to the local adapters
     * directory. On cancel or failure the partial file is deleted.
     *
     * @return a handle to cancel the download, or null if not running
     */
    public ServiceHandle downloadAdapter(String adapterName, AdapterDownloadCallback callback) {
        if (!running) {
            Platform.runLater(() -> callback.onError("Network not started"));
            return null;
        }

        Path outputPath = adaptersDir.resolve(adapterName).toAbsolutePath().normalize();

        // Path traversal guard
        if (!outputPath.startsWith(adaptersDir)) {
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
                            // Atomic rename: .part -> final name
                            Files.move(partPath, outputPath,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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

    // -- Utilities --

    private static void closeQuietly(java.io.Closeable c) {
        try { c.close(); } catch (IOException ignored) {}
    }

    private static void cleanupQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
    }
}
