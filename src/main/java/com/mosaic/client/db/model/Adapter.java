package com.mosaic.client.db.model;

/**
 * Represents a locally available adapter (maps to the Local_Adapters table).
 */
public class Adapter {

    private String adapterId;   // UUID string
    private String name;
    private String domain;
    private String filePath;
    private String fileHash;    // SHA-256
    private int sizeMb;

    public Adapter() {}

    public Adapter(String adapterId, String name, String domain,
                   String filePath, String fileHash, int sizeMb) {
        this.adapterId = adapterId;
        this.name = name;
        this.domain = domain;
        this.filePath = filePath;
        this.fileHash = fileHash;
        this.sizeMb = sizeMb;
    }

    public String getAdapterId() { return adapterId; }
    public void setAdapterId(String adapterId) { this.adapterId = adapterId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    public int getSizeMb() { return sizeMb; }
    public void setSizeMb(int sizeMb) { this.sizeMb = sizeMb; }

    @Override
    public String toString() {
        return name + " (" + domain + ")";
    }
}
