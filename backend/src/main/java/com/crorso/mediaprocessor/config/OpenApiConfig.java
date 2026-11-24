package com.crorso.mediaprocessor.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    static final String API_KEY_SCHEME = "apiKey";

    @Bean
    OpenAPI mediaProcessorOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Crorso Media Processor API")
                        .version("1.0.0")
                        .description("Merges ordered narration MP3 parts and renders an MP4 from one still image. "
                                + "All /api/** endpoints require the X-API-Key header."))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-API-Key")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
