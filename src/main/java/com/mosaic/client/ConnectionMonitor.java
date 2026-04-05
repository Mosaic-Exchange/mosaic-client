package com.mosaic.client;

import javafx.application.Platform;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Polls {@code /api/debug} every 3 seconds and fires state-change callbacks on the JavaFX thread.
 *
 * <p>Connection states:
 * <ul>
 *   <li>{@link State#CONNECTING}    — no poll has succeeded yet</li>
 *   <li>{@link State#CONNECTED}     — at least one peer (non-self node) is alive</li>
 *   <li>{@link State#DEGRADED}      — server responded but only the self node is in the cluster</li>
 *   <li>{@link State#DISCONNECTED}  — the HTTP call threw an exception</li>
 * </ul>
 */
public class ConnectionMonitor {

    public enum State { CONNECTING, CONNECTED, DEGRADED, DISCONNECTED }

    private static final int POLL_INTERVAL_SECONDS = 3;

    private final RumorClient client;

    // Callbacks (set before start(), never changed after)
    private Consumer<State>              onConnectionStateChanged;
    private Consumer<List<RumorClient.NodeInfo>> onClusterChanged;
    private BiConsumer<String, String>   onPeerStatusChanged; // (nodeId, "ALIVE"/"DOWN")

    // Internal state — only touched on the poller thread
    private State                        currentState    = State.CONNECTING;
    private Map<String, String>          lastPeerStatus  = new HashMap<>(); // nodeId → status
    private List<RumorClient.NodeInfo>   lastCluster     = Collections.emptyList();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "connection-monitor");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pollTask;

    public ConnectionMonitor(RumorClient client) {
        this.client = client;
    }

    // -------------------------------------------------------------------------
    // Callback registration
    // -------------------------------------------------------------------------

    /** Called whenever the connection state transitions. Fired on the JavaFX thread. */
    public ConnectionMonitor onConnectionStateChanged(Consumer<State> cb) {
        this.onConnectionStateChanged = cb;
        return this;
    }

    /**
     * Called when the peer list changes (a node appeared, disappeared, or changed status).
     * Receives the full up-to-date cluster list. Fired on the JavaFX thread.
     */
    public ConnectionMonitor onClusterChanged(Consumer<List<RumorClient.NodeInfo>> cb) {
        this.onClusterChanged = cb;
        return this;
    }

    /**
     * Called when an individual peer's STATUS field changes.
     * Arguments are (nodeId, newStatus) where newStatus is typically {@code "ALIVE"} or {@code "DOWN"}.
     * Fired on the JavaFX thread.
     */
    public ConnectionMonitor onPeerStatusChanged(BiConsumer<String, String> cb) {
        this.onPeerStatusChanged = cb;
        return this;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Starts the background polling loop. Safe to call once. */
    public void start() {
        pollTask = scheduler.scheduleAtFixedRate(
            this::poll, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS
        );
    }

    /** Stops the polling loop. Called from {@code MosaicApp.stop()}. */
    public void stop() {
        if (pollTask != null) pollTask.cancel(false);
        scheduler.shutdown();
    }

    // -------------------------------------------------------------------------
    // Core poll
    // -------------------------------------------------------------------------

    private void poll() {
        List<RumorClient.NodeInfo> cluster;
        try {
            cluster = client.getClusterState();
        } catch (Exception e) {
            transitionTo(State.DISCONNECTED);
            return;
        }

        // Determine new connection state
        State newState = deriveState(cluster);
        transitionTo(newState);

        // Detect cluster-level changes (node added/removed or any status change)
        if (clusterChanged(cluster)) {
            lastCluster = cluster;
            fireClusterChanged(cluster);
        }

        // Detect per-peer status changes
        for (RumorClient.NodeInfo node : cluster) {
            if (node.self()) continue; // ignore the local node's own status
            String prev = lastPeerStatus.get(node.id());
            String curr = node.status();
            if (!curr.equals(prev)) {
                lastPeerStatus.put(node.id(), curr);
                firePeerStatusChanged(node.id(), curr);
            }
        }

        // Remove peers that disappeared from the cluster entirely
        Set<String> currentIds = new HashSet<>();
        for (RumorClient.NodeInfo n : cluster) currentIds.add(n.id());
        Iterator<Map.Entry<String, String>> it = lastPeerStatus.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> entry = it.next();
            if (!currentIds.contains(entry.getKey())) {
                String departedId = entry.getKey();
                it.remove();
                firePeerStatusChanged(departedId, "DOWN");
            }
        }
    }

    // -------------------------------------------------------------------------
    // State helpers
    // -------------------------------------------------------------------------

    private static State deriveState(List<RumorClient.NodeInfo> cluster) {
        for (RumorClient.NodeInfo node : cluster) {
            if (!node.self() && node.status().equalsIgnoreCase("ALIVE")) return State.CONNECTED;
        }
        // Server responded but no alive non-self peer exists.
        return State.DEGRADED;
    }

    private void transitionTo(State newState) {
        if (newState != currentState) {
            currentState = newState;
            fireConnectionStateChanged(newState);
        }
    }

    /**
     * Returns {@code true} if the cluster list differs from the last seen snapshot.
     * Compares by node-id set and per-node status so any meaningful change triggers the callback.
     */
    private boolean clusterChanged(List<RumorClient.NodeInfo> cluster) {
        if (cluster.size() != lastCluster.size()) return true;
        Map<String, String> prev = new HashMap<>();
        for (RumorClient.NodeInfo n : lastCluster) prev.put(n.id(), n.status());
        for (RumorClient.NodeInfo n : cluster) {
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

    private void fireClusterChanged(List<RumorClient.NodeInfo> cluster) {
        if (onClusterChanged == null) return;
        List<RumorClient.NodeInfo> snapshot = List.copyOf(cluster);
        Platform.runLater(() -> onClusterChanged.accept(snapshot));
    }

    private void firePeerStatusChanged(String nodeId, String status) {
        if (onPeerStatusChanged == null) return;
        Platform.runLater(() -> onPeerStatusChanged.accept(nodeId, status));
    }
}
