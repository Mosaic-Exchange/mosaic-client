package com.mosaic.client.ui.screens.expert;

/**
 * Lightweight record representing an expert entry.
 * Fields mirror the Local_Adapters schema + runtime status.
 */
public record Expert(String name, String domain, Source source,
                     String adapterFile, Status status) {

    public enum Source {
        LOCAL, REMOTE;

        @Override
        public String toString() {
            return switch (this) {
                case LOCAL -> "Local";
                case REMOTE -> "Remote";
            };
        }
    }

    public enum Status {
        LOADED, UNLOADED, REMOTE;

        @Override
        public String toString() {
            return switch (this) {
                case LOADED -> "Loaded";
                case UNLOADED -> "Unloaded";
                case REMOTE -> "(Remote)";
            };
        }
    }
}
