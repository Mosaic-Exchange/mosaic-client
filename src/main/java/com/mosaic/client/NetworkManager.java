package com.mosaic.client;

import javafx.application.Platform;
import org.rumor.app.FileDownloadService;
import org.rumor.app.InferenceService;
import org.rumor.gossip.EndpointState;
import org.rumor.gossip.NodeId;
import org.rumor.gossip.VersionedValue;
import org.rumor.node.NodeType;
import org.rumor.node.Rumor;
import org.rumor.node.RumorConfig;
import org.rumor.service.RService;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Manages the local Rumor node lifecycle and fires state-change callbacks on the JavaFX thread.
 *
 * <p>This replaces the old ExchangeServerProcess + RumorClient + ConnectionMonitor triple.
 * The client IS a Rumor node — no subprocess or HTTP proxy needed.
 *
 * <p>Connection states:
 * <ul>
 *   <li>{@link State#CONNECTING}   — node started but no poll has completed yet</li>
 *   <li>{@link State#CONNECTED}    — at least one live non-self peer in the cluster</li>
 *   <li>{@link State#DEGRADED}     — gossip replied but only the local node is visible</li>
 *   <li>{@link State#DISCONNECTED} — gossip threw an exception</li>
 * </ul>
 */
public class NetworkManager {

    public enum State { CONNECTING, CONNECTED, DEGRADED, DISCONNECTED }

    /**
     * Snapshot of a single cluster node, derived from gossip state.
     *
     * @param sharedFiles raw {@code FileDownloadService.SHARED_FILES} gossip value,
     *                    e.g. {@code "model.gguf:4096000,other.gguf:99"} — empty when none
     */
    public record NodeSnapshot(String id, String type, String status,
                               List<String> services, boolean self, String sharedFiles) {}

    private static final int POLL_INTERVAL_SECONDS = 1;

    // Rumor node + services
    private Rumor               rumor;
    private InferenceService    inferenceService;
    private FileDownloadService fileDownloadService;

    // Background polling
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "network-monitor");
        t.setDaemon(true);
        return t;
    });

    // Snapshot state — written on the poller thread, read from any thread
    private volatile State              currentState  = State.CONNECTING;
    private volatile List<NodeSnapshot> lastCluster   = Collections.emptyList();
    private          Map<String, String> lastPeerStatus = new HashMap<>();

    // Callbacks (registered before start(), never changed after)
    private Consumer<State>              onConnectionStateChanged;
    private Consumer<List<NodeSnapshot>> onClusterChanged;
    private BiConsumer<String, String>   onPeerStatusChanged;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Creates and starts the local Rumor node, registers services, and begins polling.
     *
     * @param localHost this machine's listen address (e.g. "127.0.0.1" or LAN IP)
     * @param localPort this node's TCP port
     * @param seedHost  a known peer to bootstrap from
     * @param seedPort  that peer's TCP port
     */
    public void start(String localHost, int localPort, String seedHost, int seedPort) throws Exception {
        RumorConfig config = new RumorConfig()
            .host(localHost)
            .port(localPort)
            .nodeType(NodeType.BASIC)
            .addSeed(seedHost, seedPort);

        rumor = new Rumor(config);

        inferenceService    = new InferenceService();
        fileDownloadService = new FileDownloadService(
            Path.of(System.getProperty("user.home"), "mosaic-shared")
        );

        rumor.register(inferenceService,    new RService.Config().remoteThreads(2).localThreads(2));
        rumor.register(fileDownloadService, new RService.Config().remoteThreads(2).localThreads(2));

        rumor.start();

        scheduler.scheduleAtFixedRate(this::poll, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        System.out.println("[NetworkManager] Rumor node started at " + localHost + ":" + localPort
            + " (seed=" + seedHost + ":" + seedPort + ")");
    }

    /** Shuts down the background poller and the Rumor node. */
    public void stop() {
        scheduler.shutdown();
        if (rumor != null) rumor.stop();
        System.out.println("[NetworkManager] Stopped.");
    }

    // -------------------------------------------------------------------------
    // Service accessors
    // -------------------------------------------------------------------------

    /** The registered {@link InferenceService} — use {@code dispatch()} or {@code request()}. */
    public InferenceService inferenceService() { return inferenceService; }

    /** The registered {@link FileDownloadService} — use {@code downloadFrom()} for downloads. */
    public FileDownloadService fileDownloadService() { return fileDownloadService; }

    // -------------------------------------------------------------------------
    // Snapshot accessors (safe to call from any thread)
    // -------------------------------------------------------------------------

    /** Current connection state as of the last completed poll. */
    public State getCurrentState() { return currentState; }

    /** Cluster snapshot as of the last completed poll. Empty list before first poll. */
    public List<NodeSnapshot> getLastCluster() { return List.copyOf(lastCluster); }

    // -------------------------------------------------------------------------
    // Callback registration (call before start())
    // -------------------------------------------------------------------------

    /** Called whenever the connection state transitions. Fired on the JavaFX thread. */
    public NetworkManager onConnectionStateChanged(Consumer<State> cb) {
        this.onConnectionStateChanged = cb;
        return this;
    }

    /**
     * Called when the peer list changes (node appeared, disappeared, or changed status).
     * Receives the full up-to-date cluster list. Fired on the JavaFX thread.
     */
    public NetworkManager onClusterChanged(Consumer<List<NodeSnapshot>> cb) {
        this.onClusterChanged = cb;
        return this;
    }

    /**
     * Called when an individual peer's STATUS field changes.
     * Arguments are (nodeId, newStatus) where newStatus is typically {@code "ALIVE"} or {@code "DOWN"}.
     * Fired on the JavaFX thread.
     */
    public NetworkManager onPeerStatusChanged(BiConsumer<String, String> cb) {
        this.onPeerStatusChanged = cb;
        return this;
    }

    // -------------------------------------------------------------------------
    // Core poll — runs on the scheduler thread every 1 s
    // -------------------------------------------------------------------------

    private void poll() {
        if (rumor == null) return;

        Map<NodeId, EndpointState> raw;
        try {
            raw = rumor.getClusterState();
        } catch (Exception e) {
            transitionTo(State.DISCONNECTED);
            return;
        }

        List<NodeSnapshot> cluster = buildCluster(raw);
        State newState = deriveState(cluster);
        transitionTo(newState);

        if (clusterChanged(cluster)) {
            lastCluster = cluster;
            fireClusterChanged(cluster);
        }

        // Per-peer status change detection
        for (NodeSnapshot node : cluster) {
            if (node.self()) continue;
            String prev = lastPeerStatus.get(node.id());
            String curr = node.status();
            if (!curr.equals(prev)) {
                lastPeerStatus.put(node.id(), curr);
                firePeerStatusChanged(node.id(), curr);
            }
        }

        // Remove peers that disappeared from the cluster entirely
        Set<String> currentIds = new HashSet<>();
        for (NodeSnapshot n : cluster) currentIds.add(n.id());
        Iterator<Map.Entry<String, String>> it = lastPeerStatus.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> entry = it.next();
            if (!currentIds.contains(entry.getKey())) {
                String departed = entry.getKey();
                it.remove();
                firePeerStatusChanged(departed, "DOWN");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Gossip → NodeSnapshot mapping
    // -------------------------------------------------------------------------

    private List<NodeSnapshot> buildCluster(Map<NodeId, EndpointState> raw) {
        List<NodeSnapshot> result = new ArrayList<>();
        NodeId selfId = rumor.localId();

        for (Map.Entry<NodeId, EndpointState> entry : raw.entrySet()) {
            NodeId       nodeId = entry.getKey();
            EndpointState state = entry.getValue();

            String id          = nodeId.toString();
            String type        = appState(state, "NODE_TYPE",                          "?");
            String status      = appState(state, "STATUS",                             "?");
            String servicesCsv = appState(state, "SERVICES",                           "");
            String sharedFiles = appState(state, "FileDownloadService.SHARED_FILES",   "");

            List<String> services = servicesCsv.isEmpty()
                ? Collections.emptyList()
                : Arrays.asList(servicesCsv.split(","));

            boolean self = nodeId.equals(selfId);
            result.add(new NodeSnapshot(id, type, status, services, self, sharedFiles));
        }
        return result;
    }

    private static String appState(EndpointState state, String key, String defaultVal) {
        VersionedValue vv = state.getAppState(key);
        return vv != null ? vv.value() : defaultVal;
    }

    // -------------------------------------------------------------------------
    // State helpers
    // -------------------------------------------------------------------------

    private static State deriveState(List<NodeSnapshot> cluster) {
        for (NodeSnapshot node : cluster) {
            if (!node.self() && "ALIVE".equalsIgnoreCase(node.status())) return State.CONNECTED;
        }
        return State.DEGRADED;
    }

    private void transitionTo(State newState) {
        if (newState != currentState) {
            currentState = newState;
            fireConnectionStateChanged(newState);
        }
    }

    private boolean clusterChanged(List<NodeSnapshot> cluster) {
        if (cluster.size() != lastCluster.size()) return true;
        Map<String, String> prev = new HashMap<>();
        for (NodeSnapshot n : lastCluster) prev.put(n.id(), n.status());
        for (NodeSnapshot n : cluster) {
            if (!n.status().equals(prev.get(n.id()))) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Callback dispatch — always via Platform.runLater()
    // -------------------------------------------------------------------------

    private void fireConnectionStateChanged(State state) {
        if (onConnectionStateChanged == null) return;
        Platform.runLater(() -> onConnectionStateChanged.accept(state));
    }

    private void fireClusterChanged(List<NodeSnapshot> cluster) {
        if (onClusterChanged == null) return;
        List<NodeSnapshot> snapshot = List.copyOf(cluster);
        Platform.runLater(() -> onClusterChanged.accept(snapshot));
    }

    private void firePeerStatusChanged(String nodeId, String status) {
        if (onPeerStatusChanged == null) return;
        Platform.runLater(() -> onPeerStatusChanged.accept(nodeId, status));
    }
}
