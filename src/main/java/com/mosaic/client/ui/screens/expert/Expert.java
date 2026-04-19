package com.mosaic.client.ui.screens.expert;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class Expert {
    // Properties (accessed with methods below)
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty domain = new SimpleStringProperty();
    private final ObjectProperty<Source> source = new SimpleObjectProperty<>();
    private final StringProperty adapterFile = new SimpleStringProperty();
    private final ObjectProperty<Status> status = new SimpleObjectProperty<>();

    public Expert(String name, String domain, Source source, String adapterFile) {
        setName(name);
        setDomain(domain);
        setSource(source);
        setAdapterFile(adapterFile);
        status.set(source.equals(Source.REMOTE) ? Status.REMOTE : Status.UNLOADED);
    }

    public boolean isRemote() {
        return source.get().equals(Source.REMOTE);
    }

    public boolean isLoaded() {
        return status.get().equals(Status.LOADED);
    }

    // Server-side ID (used for queries)
    public String serverSideId;

    // Getters
    public String getName() { return name.get(); }
    public String getDomain() { return domain.get(); }
    public Source getSource() { return source.get(); }
    public String getAdapterFile() { return adapterFile.get(); }
    public Status getStatus() { return status.get(); }

    // Setters
    public void setName(String value) { name.set(value); }
    public void setDomain(String value) { domain.set(value); }
    public void setSource(Source value) { source.set(value); }
    public void setAdapterFile(String value) { adapterFile.set(value); }

    // Expose read-only properties
    public ReadOnlyStringProperty nameProperty() { return name; }
    public ReadOnlyStringProperty domainProperty() { return domain; }
    public ReadOnlyObjectProperty<Source> sourceProperty() { return source; }
    public ReadOnlyStringProperty adapterFileProperty() { return adapterFile; }
    public ReadOnlyObjectProperty<Status> statusProperty() { return status; }

    // Status Property
    public void load(String newServerSideId) {
        status.set(Status.LOADED);
        serverSideId = newServerSideId;
    }
    public void unload() {
        status.set(Status.UNLOADED);
        serverSideId = null;
    }

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
