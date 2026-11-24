package com.crorso.mediaprocessor.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Shared Spring context for API tests. All tests use one temp root that is removed when the JVM exits.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractApiTest {

    protected static final String API_KEY = "test-only-api-key-0123456789abcdefghijklmnop";
    protected static final Path TEST_ROOT = createTestRoot();
    protected static final Path TEMP_DIR = TEST_ROOT.resolve("temp");
    protected static final Path OUTPUT_DIR = TEST_ROOT.resolve("output");

    @Autowired
    protected MockMvc mockMvc;

    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("media.api-key", () -> API_KEY);
        registry.add("media.temp-dir", () -> TEMP_DIR.toString());
        registry.add("media.output-dir", () -> OUTPUT_DIR.toString());
        registry.add("media.max-file-size-mb", () -> 1);
        registry.add("media.max-audio-parts", () -> 200);
    }

    protected static MockMultipartFile imageUpload(String filename, byte[] bytes) {
        return new MockMultipartFile("image", filename, "image/png", bytes);
    }

    protected static MockMultipartFile partUpload(String filename, byte[] bytes) {
        return new MockMultipartFile("audioParts", filename, "audio/mpeg", bytes);
    }

    protected static MockHttpServletRequestBuilder processRequest(String storyId, MockMultipartFile image,
                                                                  MockMultipartFile... parts) {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/media/process");
        builder.file(image);
        builder.param("storyId", storyId);
        for (MockMultipartFile part : parts) {
            builder.file(part);
        }
        return builder.header("X-API-Key", API_KEY);
    }

    private static Path createTestRoot() {
        try {
            Path root = Files.createTempDirectory("crorso-media-test-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(root)));
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("Could not create test directory", e);
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best-effort cleanup of test output
        }
    }
}
