package com.mosaic.client.service;

import java.io.Serializable;

/**
 * Request payload for {@link InferenceService}.
 *
 * @param prompt          the user's prompt text
 * @param model           LLM model name (e.g. "llama3.2"); {@code null} uses the service default
 * @param maxOutputTokens maximum tokens to generate; {@code 0} means no limit
 * @param temperature     sampling temperature; {@code 0} means use model default
 */
public record InferenceRequest(
        String prompt,
        String model,
        int maxOutputTokens,
        double temperature
) implements Serializable {

    public InferenceRequest(String prompt) {
        this(prompt, null, 0, 0);
    }

    public InferenceRequest(String prompt, String model) {
        this(prompt, model, 0, 0);
    }
}
