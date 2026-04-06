package com.mosaic.client;

import javafx.application.Platform;
import org.rumor.app.InferenceRequest;
import org.rumor.app.InferenceService;
import org.rumor.service.RequestEvent;
import org.rumor.service.ServiceHandle;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Manages a single inference request lifecycle via the RService API.
 *
 * <p>States:
 * <ul>
 *   <li>{@link State#WAITING}   — request dispatched, waiting for first response</li>
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

    private final InferenceService inferenceService;

    private Runnable         onWaiting;
    private Consumer<String> onToken;
    private Runnable         onDone;
    private Consumer<String> onError;
    private Runnable         onCancelled;

    private volatile ServiceHandle handle;
    private volatile boolean       cancelled = false;

    public InferenceSession(InferenceService inferenceService) {
        this.inferenceService = inferenceService;
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
     *
     * <p>For {@code isLocal = true}, the request is executed on this node via
     * {@link InferenceService#request}. For {@code isLocal = false}, it is dispatched
     * to a remote peer via {@link InferenceService#dispatch}.
     *
     * @param prompt  the user prompt
     * @param isLocal {@code true} = run on this node; {@code false} = dispatch to network
     * @param model   model/adapter filename (may be {@code null} to use service default)
     */
    public void start(String prompt, boolean isLocal, String model) {
        Platform.runLater(() -> { if (onWaiting != null) onWaiting.run(); });

        InferenceRequest req = new InferenceRequest(prompt, model);

        // The RService callback is invoked on the framework's thread — always use
        // Platform.runLater() before touching any JavaFX node.
        if (isLocal) {
            handle = inferenceService.request(req, this::onEvent);
        } else {
            handle = inferenceService.dispatch(req, this::onEvent);
        }
    }

    /**
     * Cancels the active inference request.
     * Fires {@code onCancelled} on the FX thread once the handle is cancelled.
     */
    public void cancel() {
        cancelled = true;
        ServiceHandle h = handle;
        if (h != null) {
            h.cancel();
        }
        // onEvent will receive RequestEvent.Cancelled and fire onCancelled for us.
        // If dispatch hasn't returned the handle yet, the cancelled flag above is
        // checked before every callback, so no spurious callbacks will fire.
    }

    // -------------------------------------------------------------------------
    // RService event handler — called on the framework thread
    // -------------------------------------------------------------------------

    private void onEvent(RequestEvent<byte[]> event) {
        switch (event) {
            case RequestEvent.Processing<?> p -> {
                // Already fired onWaiting via Platform.runLater in start() — no-op here.
            }
            case RequestEvent.StreamData<?> sd -> {
                if (cancelled) return;
                String token = new String(sd.raw(), StandardCharsets.UTF_8);
                Platform.runLater(() -> { if (onToken != null) onToken.accept(token); });
            }
            case RequestEvent.Succeeded<?> s -> {
                if (cancelled) return;
                Platform.runLater(() -> { if (onDone != null) onDone.run(); });
            }
            case RequestEvent.Failed<?> f -> {
                if (cancelled) return;
                Platform.runLater(() -> { if (onError != null) onError.accept(f.reason()); });
            }
            case RequestEvent.Cancelled<?> c ->
                Platform.runLater(() -> { if (onCancelled != null) onCancelled.run(); });
        }
    }
}
