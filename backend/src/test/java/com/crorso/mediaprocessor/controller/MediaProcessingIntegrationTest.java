package com.crorso.mediaprocessor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.crorso.mediaprocessor.support.FfmpegFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests with real FFmpeg. Inputs are synthetic and generated per run. The class is skipped
 * (and reported as skipped) when FFmpeg or FFprobe is not installed.
 */
@EnabledIf("ffmpegAvailable")
class MediaProcessingIntegrationTest extends AbstractApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double TOLERANCE_SECONDS = 0.25;

    @TempDir
    Path fixtures;

    static boolean ffmpegAvailable() {
        return FfmpegFixtures.isAvailable();
    }

    @Test
    void mergesPartsInNumericOrderAndPublishesVerifiedMp3AndMp4() throws Exception {
        byte[] image = Files.readAllBytes(FfmpegFixtures.solidPng(fixtures, "bg.png", 1280, 720));
        byte[] part1 = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "p1.mp3", 1.0, 44_100, 2));
        byte[] part2 = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "p2.mp3", 1.5, 44_100, 2));
        byte[] part3 = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "p3.mp3", 0.5, 44_100, 2));

        // Submitted out of order on purpose: numbering must come from the filename.
        MvcResult result = mockMvc.perform(processRequest("IT-STORY-1", imageUpload("bg.png", image),
                        partUpload("IT-STORY-1_part_003.mp3", part3),
                        partUpload("IT-STORY-1_part_001.mp3", part1),
                        partUpload("IT-STORY-1_part_002.mp3", part2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.mergedAudioFilename").value("IT-STORY-1_full.mp3"))
                .andExpect(jsonPath("$.videoFilename").value("IT-STORY-1.mp4"))
                .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("audioDurationSeconds").asDouble()).isCloseTo(3.0, within(TOLERANCE_SECONDS));
        assertThat(body.get("videoDurationSeconds").asDouble()).isCloseTo(3.0, within(TOLERANCE_SECONDS));
        assertThat(body.get("audioDownloadUrl").asText()).endsWith("/api/v1/media/IT-STORY-1/audio");

        byte[] audioBytes = download("/api/v1/media/IT-STORY-1/audio", "audio/mpeg");
        assertThat(audioBytes.length).isEqualTo(body.get("mergedAudioSizeBytes").asLong());
        Path audioFile = FfmpegFixtures.writeBytes(fixtures, "downloaded.mp3", audioBytes);
        assertThat(FfmpegFixtures.probe(audioFile, "a:0", "stream=codec_name,sample_rate,channels")).isEqualTo("mp3,44100,2");
        assertThat(FfmpegFixtures.duration(audioFile)).isCloseTo(3.0, within(TOLERANCE_SECONDS));

        byte[] videoBytes = download("/api/v1/media/IT-STORY-1/video", "video/mp4");
        assertThat(videoBytes.length).isEqualTo(body.get("videoSizeBytes").asLong());
        Path videoFile = FfmpegFixtures.writeBytes(fixtures, "downloaded.mp4", videoBytes);
        assertThat(FfmpegFixtures.probe(videoFile, "v:0", "stream=codec_name,width,height,pix_fmt")).isEqualTo("h264,1920,1080,yuv420p");
        assertThat(FfmpegFixtures.probe(videoFile, "a:0", "stream=codec_name")).isEqualTo("aac");
        assertThat(FfmpegFixtures.duration(videoFile)).isCloseTo(3.0, within(TOLERANCE_SECONDS));
    }

    @Test
    void reprocessingReplacesPreviousOutputs() throws Exception {
        byte[] image = Files.readAllBytes(FfmpegFixtures.solidPng(fixtures, "bg2.png", 640, 360));
        byte[] first = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "r1.mp3", 2.0, 44_100, 2));
        byte[] replacement = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "r2.mp3", 1.0, 44_100, 2));

        mockMvc.perform(processRequest("IT-REPLACE", imageUpload("bg2.png", image),
                        partUpload("IT-REPLACE_part_001.mp3", first)))
                .andExpect(status().isOk());

        MvcResult second = mockMvc.perform(processRequest("IT-REPLACE", imageUpload("bg2.png", image),
                        partUpload("IT-REPLACE_part_001.mp3", replacement)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(JSON.readTree(second.getResponse().getContentAsString()).get("audioDurationSeconds").asDouble())
                .isCloseTo(1.0, within(TOLERANCE_SECONDS));

        Path audioFile = FfmpegFixtures.writeBytes(fixtures, "replaced.mp3",
                download("/api/v1/media/IT-REPLACE/audio", "audio/mpeg"));
        assertThat(FfmpegFixtures.duration(audioFile)).isCloseTo(1.0, within(TOLERANCE_SECONDS));
    }

    @Test
    void incompatiblePartFormatsAreNormalisedAndJoined() throws Exception {
        byte[] image = Files.readAllBytes(FfmpegFixtures.solidPng(fixtures, "bg3.png", 800, 600));
        byte[] stereo = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "s.mp3", 1.0, 44_100, 2));
        byte[] mono22k = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "m.mp3", 1.0, 22_050, 1));

        mockMvc.perform(processRequest("IT-MIXED", imageUpload("bg3.png", image),
                        partUpload("IT-MIXED_part_001.mp3", stereo),
                        partUpload("IT-MIXED_part_002.mp3", mono22k)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audioDurationSeconds").value(org.hamcrest.Matchers.closeTo(2.0, TOLERANCE_SECONDS)));

        Path audioFile = FfmpegFixtures.writeBytes(fixtures, "mixed.mp3",
                download("/api/v1/media/IT-MIXED/audio", "audio/mpeg"));
        assertThat(FfmpegFixtures.probe(audioFile, "a:0", "stream=sample_rate,channels")).isEqualTo("44100,2");
    }

    @Test
    void gapsAreRejectedUnlessExplicitlyAllowed() throws Exception {
        byte[] image = Files.readAllBytes(FfmpegFixtures.solidPng(fixtures, "bg4.png", 640, 360));
        byte[] a = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "g1.mp3", 0.5, 44_100, 2));
        byte[] b = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "g10.mp3", 0.5, 44_100, 2));

        mockMvc.perform(processRequest("IT-GAPS", imageUpload("bg4.png", image),
                        partUpload("IT-GAPS_part_001.mp3", a),
                        partUpload("IT-GAPS_part_010.mp3", b)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing part number 2."));

        mockMvc.perform(processRequest("IT-GAPS", imageUpload("bg4.png", image),
                        partUpload("IT-GAPS_part_001.mp3", a),
                        partUpload("IT-GAPS_part_010.mp3", b))
                        .param("allowGaps", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void manyShortPartsDoNotAccumulateExtraSilence() throws Exception {
        byte[] image = Files.readAllBytes(FfmpegFixtures.solidPng(fixtures, "bg7.png", 640, 360));
        MockMultipartFile[] parts = new MockMultipartFile[12];
        for (int i = 0; i < parts.length; i++) {
            byte[] bytes = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "m" + i + ".mp3", 0.5, 44_100, 2));
            parts[i] = partUpload(String.format("IT-MANY_part_%03d.mp3", i + 1), bytes);
        }

        // Stream-copying 12 MP3 segments added about 0.5 s of encoder padding; a single join pass must not.
        mockMvc.perform(processRequest("IT-MANY", imageUpload("bg7.png", image), parts))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audioDurationSeconds").value(org.hamcrest.Matchers.closeTo(6.0, 0.15)));
    }

    @Test
    void containModeHonoursRequestedDimensions() throws Exception {
        byte[] image = Files.readAllBytes(FfmpegFixtures.solidPng(fixtures, "tall.png", 400, 800));
        byte[] audio = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "c.mp3", 1.0, 44_100, 2));

        mockMvc.perform(processRequest("IT-CONTAIN", imageUpload("tall.png", image),
                        partUpload("IT-CONTAIN_part_001.mp3", audio))
                        .param("imageFit", "contain")
                        .param("videoWidth", "640")
                        .param("videoHeight", "360")
                        .param("backgroundColor", "#202020"))
                .andExpect(status().isOk());

        Path videoFile = FfmpegFixtures.writeBytes(fixtures, "contain.mp4",
                download("/api/v1/media/IT-CONTAIN/video", "video/mp4"));
        assertThat(FfmpegFixtures.probe(videoFile, "v:0", "stream=width,height")).isEqualTo("640,360");
    }

    @Test
    void corruptAudioIsRejectedAndDoesNotReplaceExistingOutputs() throws Exception {
        byte[] image = Files.readAllBytes(FfmpegFixtures.solidPng(fixtures, "bg5.png", 640, 360));
        byte[] good = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "k.mp3", 1.0, 44_100, 2));
        mockMvc.perform(processRequest("IT-CORRUPT", imageUpload("bg5.png", image),
                        partUpload("IT-CORRUPT_part_001.mp3", good)))
                .andExpect(status().isOk());

        byte[] corrupt = new byte[4096];
        new java.util.Random(42).nextBytes(corrupt);
        System.arraycopy("ID3\u0004\u0000\u0000".getBytes(), 0, corrupt, 0, 6);

        mockMvc.perform(processRequest("IT-CORRUPT", imageUpload("bg5.png", image),
                        partUpload("IT-CORRUPT_part_001.mp3", corrupt)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIO"));

        Path audioFile = FfmpegFixtures.writeBytes(fixtures, "still-good.mp3",
                download("/api/v1/media/IT-CORRUPT/audio", "audio/mpeg"));
        assertThat(FfmpegFixtures.duration(audioFile)).isCloseTo(1.0, within(TOLERANCE_SECONDS));
    }

    @Test
    void deletionRemovesOutputsForever() throws Exception {
        byte[] image = Files.readAllBytes(FfmpegFixtures.solidPng(fixtures, "bg6.png", 640, 360));
        byte[] audio = Files.readAllBytes(FfmpegFixtures.sineMp3(fixtures, "d.mp3", 0.5, 44_100, 2));
        mockMvc.perform(processRequest("IT-DELETE", imageUpload("bg6.png", image),
                        partUpload("IT-DELETE_part_001.mp3", audio)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/media/IT-DELETE").header("X-API-Key", API_KEY))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/media/IT-DELETE/video").header("X-API-Key", API_KEY))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/media/IT-DELETE/audio").header("X-API-Key", API_KEY))
                .andExpect(status().isNotFound());
    }

    private byte[] download(String path, String expectedContentType) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(expectedContentType))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();
        return result.getResponse().getContentAsByteArray();
    }
}
