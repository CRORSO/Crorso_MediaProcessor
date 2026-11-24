package com.crorso.mediaprocessor.controller;

import com.crorso.mediaprocessor.support.FfmpegFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chunk-by-chunk workflow: stage parts in separate requests, in any order, then render from the staged set.
 * Requires FFmpeg; the class is skipped and reported as skipped when it is missing.
 */
@EnabledIf("ffmpegAvailable")
class StagedProcessingIntegrationTest extends AbstractApiTest {

    @TempDir
    Path fixtures;

    static boolean ffmpegAvailable() {
        return FfmpegFixtures.isAvailable();
    }

    @Test
    void stagedPartsRenderInNumericOrderAndAreClearedAfterSuccess() throws Exception {
        String story = "STAGED-1";
        byte[] image = Files.readAllBytes(FfmpegFixtures.solidPng(fixtures, "staged.png", 640, 360));
        byte[] first = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "s1.mp3", 1.0, 44_100, 2));
        byte[] second = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "s2.mp3", 1.5, 44_100, 2));
        byte[] third = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "s3.mp3", 0.5, 44_100, 2));

        // Stage out of order, as an iterator might.
        stage(story, 3, third);
        stage(story, 1, first);
        stage(story, 2, second);

        mockMvc.perform(get("/api/v1/media/" + story + "/parts").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partNumbers", contains(1, 2, 3)));

        mockMvc.perform(multipart("/api/v1/media/process")
                        .file(new MockMultipartFile("image", "staged.png", "image/png", image))
                        .param("storyId", story)
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audioDurationSeconds", closeTo(3.0, 0.2)));

        mockMvc.perform(get("/api/v1/media/" + story + "/parts").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partNumbers", empty()));
    }

    @Test
    void failedRenderKeepsStagedPartsForRetry() throws Exception {
        String story = "STAGED-RETRY";
        byte[] image = Files.readAllBytes(FfmpegFixtures.solidPng(fixtures, "retry.png", 640, 360));
        stage(story, 1, Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "r1.mp3", 1.0, 44_100, 2)));
        stage(story, 3, Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "r3.mp3", 1.0, 44_100, 2)));

        // Part 2 is missing, so the render is refused. The staged parts must remain for a corrected retry.
        mockMvc.perform(multipart("/api/v1/media/process")
                        .file(new MockMultipartFile("image", "retry.png", "image/png", image))
                        .param("storyId", story)
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing part number 2."));

        mockMvc.perform(get("/api/v1/media/" + story + "/parts").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partNumbers", contains(1, 3)));
    }

    @Test
    void corruptChunkIsRejectedOnArrival() throws Exception {
        byte[] corrupt = new byte[4096];
        new java.util.Random(7).nextBytes(corrupt);
        System.arraycopy("ID3\u0004\u0000\u0000".getBytes(), 0, corrupt, 0, 6);

        mockMvc.perform(multipart("/api/v1/media/STAGED-BAD/parts")
                        .file(new MockMultipartFile("audioPart", "bad.mp3", "audio/mpeg", corrupt))
                        .param("partNumber", "1")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIO"));

        mockMvc.perform(get("/api/v1/media/STAGED-BAD/parts").header("X-API-Key", API_KEY))
                .andExpect(jsonPath("$.partNumbers", empty()));
    }

    private void stage(String storyId, int partNumber, byte[] bytes) throws Exception {
        mockMvc.perform(multipart("/api/v1/media/" + storyId + "/parts")
                        .file(new MockMultipartFile("audioPart", "chunk.mp3", "audio/mpeg", bytes))
                        .param("partNumber", String.valueOf(partNumber))
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STAGED"))
                .andExpect(jsonPath("$.filename").value(storyId + "_part_" + String.format("%03d", partNumber) + ".mp3"));
    }
}
