package com.crorso.mediaprocessor.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start in the {@code prod} profile unless a strong API key is configured.
 * The error message never includes the key value.
 */
@Component
@Profile("prod")
public class ProductionSettingsValidator implements InitializingBean {

    static final int MIN_API_KEY_LENGTH = 32;

    private final MediaProperties properties;

    public ProductionSettingsValidator(MediaProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("API_KEY must be set when running with the prod profile");
        }
        if (apiKey.length() < MIN_API_KEY_LENGTH) {
            throw new IllegalStateException("API_KEY must be at least " + MIN_API_KEY_LENGTH + " characters in the prod profile");
        }
    }
}
