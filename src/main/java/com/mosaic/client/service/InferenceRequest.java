package com.mosaic.client.service;

import java.io.Serializable;

/**
 * Request payload for {@link InferenceService}.
 *
 * @param message          the user's message text
 * @param adapter_id       LLM model name (e.g. "llama3.2"); {@code null} uses the service default
 * @param maxOutputTokens maximum tokens to generate; {@code 0} means no limit
 * @param request_id       ID used to track the request (default: use private counter)
 */
public record InferenceRequest(
        String message,
        String adapter_id,
        int maxOutputTokens,
        int request_id
) implements Serializable {
    private static int lastId = 0;

    public InferenceRequest(String prompt) {
        this(prompt, "", 0, lastId++);
    }

    public InferenceRequest(String prompt, String adapter_id) {
        this(prompt, adapter_id, 0, lastId++);
    }
}
