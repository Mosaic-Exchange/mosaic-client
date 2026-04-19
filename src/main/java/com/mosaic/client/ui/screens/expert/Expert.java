package com.mosaic.client.ui.screens.expert;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class Expert {
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty domain = new SimpleStringProperty();
    private final ObjectProperty<Source> source = new SimpleObjectProperty<>();
    private final StringProperty adapterFile = new SimpleStringProperty();
    private final ObjectProperty<Status> status = new SimpleObjectProperty<>();

    public Expert(String name, String domain, Source source, String adapterFile, Status status) {
        setName(name);
        setDomain(domain);
        setSource(source);
        setAdapterFile(adapterFile);
        setStatus(status);
    }

    // Name Property
    public String getName() { return name.get(); }
    public void setName(String value) { name.set(value); }
    public ReadOnlyStringProperty nameProperty() { return name; }

    // Domain Property
    public String getDomain() { return domain.get(); }
    public void setDomain(String value) { domain.set(value); }
    public ReadOnlyStringProperty domainProperty() { return domain; }

    // Source Property
    public Source getSource() { return source.get(); }
    public void setSource(Source value) { source.set(value); }
    public ReadOnlyObjectProperty<Source> sourceProperty() { return source; }

    // AdapterFile Property
    public String getAdapterFile() { return adapterFile.get(); }
    public void setAdapterFile(String value) { adapterFile.set(value); }
    public ReadOnlyStringProperty adapterFileProperty() { return adapterFile; }

    // Status Property
    public Status getStatus() { return status.get(); }
    public void setStatus(Status value) { status.set(value); }
    public ReadOnlyObjectProperty<Status> statusProperty() { return status; }

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
