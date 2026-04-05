package com.mosaic.client;

import javafx.application.Platform;

import java.util.function.Consumer;

/**
 * Manages a single inference request lifecycle.
 *
 * <p>States:
 * <ul>
 *   <li>{@link State#WAITING}   — request sent, waiting for first response from server</li>
 *   <li>{@link State#STREAMING} — tokens are arriving</li>
 *   <li>{@link State#DONE}      — inference completed normally</li>
 *   <li>{@link State#ERROR}     — inference failed</li>
 *   <li>{@link State#CANCELLED} — cancelled by the user</li>
 * </ul>
 *
 * <p>All callbacks are guaranteed to be invoked on the JavaFX application thread.
 */
public class InferenceSession {

    public enum State { WAITING, STREAMING, DONE, ERROR, CANCELLED }

    private final RumorClient client;

    private Runnable         onWaiting;
    private Consumer<String> onToken;
    private Runnable         onDone;
    private Consumer<String> onError;
    private Runnable         onCancelled;

    private volatile boolean cancelled = false;

    public InferenceSession(RumorClient client) {
        this.client = client;
    }

    // -------------------------------------------------------------------------
    // Callback registration (builder-style)
    // -------------------------------------------------------------------------

    public InferenceSession onWaiting(Runnable cb)         { onWaiting   = cb; return this; }
    public InferenceSession onToken(Consumer<String> cb)   { onToken     = cb; return this; }
    public InferenceSession onDone(Runnable cb)            { onDone      = cb; return this; }
    public InferenceSession onError(Consumer<String> cb)   { onError     = cb; return this; }
    public InferenceSession onCancelled(Runnable cb)       { onCancelled = cb; return this; }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Starts the inference request.
     * Immediately fires {@code onWaiting} on the FX thread, then streams tokens as they arrive.
     *
     * @param prompt  the user prompt
     * @param isLocal {@code true} = run on this node; {@code false} = dispatch to network
     * @param model   model/adapter filename (may be {@code null} to use server default)
     */
    public void start(String prompt, boolean isLocal, String model) {
        // Enter WAITING state right away — no need to wait for HTTP round-trip.
        Platform.runLater(() -> { if (onWaiting != null) onWaiting.run(); });

        client.sendMessage(
            prompt, model, isLocal,
            // onToken — called on RumorClient's virtual thread
            token -> {
                if (cancelled) return;
                Platform.runLater(() -> { if (onToken != null) onToken.accept(token); });
            },
            // onDone
            () -> {
                if (cancelled) return;
                Platform.runLater(() -> { if (onDone != null) onDone.run(); });
            },
            // onError
            reason -> {
                if (cancelled) return;
                Platform.runLater(() -> { if (onError != null) onError.accept(reason); });
            }
        );
    }

    /**
     * Cancels the active inference request.
     * Fires {@code onCancelled} on the FX thread once the server acknowledges cancellation.
     */
    public void cancel() {
        cancelled = true;
        Thread.ofVirtual().start(() -> {
            try { client.cancelInference(); } catch (Exception ignored) {}
            Platform.runLater(() -> { if (onCancelled != null) onCancelled.run(); });
        });
    }
}
