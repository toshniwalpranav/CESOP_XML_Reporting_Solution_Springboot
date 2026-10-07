package com.example.cesop.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cesop")
public record CesopProperties(List<String> xsdFiles,
                              String xsdBasePath,
                              String defaultEnvironment,
                              String defaultTransmittingCountry) {
    public CesopProperties {
        xsdFiles = xsdFiles == null ? List.of() : xsdFiles;
        xsdBasePath = xsdBasePath == null || xsdBasePath.isBlank() ? "Amtlicher_Datensatz_CESOP" : xsdBasePath;
        defaultEnvironment = defaultEnvironment == null || defaultEnvironment.isBlank() ? "TEST" : defaultEnvironment;
        defaultTransmittingCountry = defaultTransmittingCountry == null || defaultTransmittingCountry.isBlank()
                ? "DE" : defaultTransmittingCountry;
    }
}
