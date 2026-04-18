package com.mosaic.client.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Path;

public record AdapterMetadata(
    String name,
    String domain
) {
    public static AdapterMetadata fromFile(Path configFile) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(configFile.toFile(), AdapterMetadata.class);
    }

    public void toFile(Path configFile) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.writeValue(configFile.toFile(), this);
    }
}
