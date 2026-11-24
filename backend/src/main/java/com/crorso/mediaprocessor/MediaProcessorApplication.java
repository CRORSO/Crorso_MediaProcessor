package com.crorso.mediaprocessor;

import com.crorso.mediaprocessor.config.MediaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = MediaProperties.class)
@EnableScheduling
public class MediaProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaProcessorApplication.class, args);
    }
}
