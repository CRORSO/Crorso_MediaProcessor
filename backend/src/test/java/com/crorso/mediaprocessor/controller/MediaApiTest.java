package com.crorso.mediaprocessor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the HTTP contract without FFmpeg: authentication, input validation, error formatting
 * and download authorization. Every request here fails before any media tool would run.
 */
class MediaApiTest extends AbstractApiTest {

    private static final byte[] PNG_HEADER = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13};
    private static final byte[] MP3_HEADER = "ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);

    // Authentication

    @Test
    void processWithoutApiKeyIsUnauthorizedWithJsonError() throws Exception {
        mockMvc.perform(multipart("/api/v1/media/process")
                        .file(imageUpload("bg.png", PNG_HEADER))
                        .file(partUpload("STORY-1_part_001.mp3", MP3_HEADER))
                        .param("storyId", "STORY-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void wrongApiKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/media/STORY-1/audio").header("X-API-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bearerTokenIsAcceptedAsAlternative() throws Exception {
        mockMvc.perform(get("/api/v1/media/STORY-1/audio").header("Authorization", "Bearer " + API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadsAndDeletionRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/media/STORY-1/video")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/media/STORY-1")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthEndpointIsReachableWithoutApiKey() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    // Input validation

    @Test
    void invalidStoryIdIsBadRequest() throws Exception {
        mockMvc.perform(processRequest("../escape", imageUpload("bg.png", PNG_HEADER),
                        partUpload("x_part_001.mp3", MP3_HEADER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STORY_ID"));
    }

    @Test
    void processWithoutUploadedOrStagedPartsIsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/v1/media/process")
                        .file(imageUpload("bg.png", PNG_HEADER))
                        .param("storyId", "NOTHING-STAGED")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIO_PARTS"));
    }

    @Test
    void stagingRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/media/STORY-1/parts")).andExpect(status().isUnauthorized());
    }

    @Test
    void stagingRejectsPartNumbersOutOfRange() throws Exception {
        mockMvc.perform(multipart("/api/v1/media/STORY-1/parts")
                        .file(new MockMultipartFile("audioPart", "x.mp3", "audio/mpeg", MP3_HEADER))
                        .param("partNumber", "0")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIO_PARTS"));
    }

    @Test
    void stagingRejectsExecutablesAsAudio() throws Exception {
        byte[] windowsExecutable = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};
        mockMvc.perform(multipart("/api/v1/media/STORY-1/parts")
                        .file(new MockMultipartFile("audioPart", "x.mp3", "audio/mpeg", windowsExecutable))
                        .param("partNumber", "1")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIO"));
    }

    @Test
    void listingPartsForUnknownStoryIsEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/media/NEVER-STAGED/parts").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partNumbers").isEmpty());
    }

    @Test
    void duplicatePartNumbersAreRejected() throws Exception {
        mockMvc.perform(processRequest("STORY-1", imageUpload("bg.png", PNG_HEADER),
                        partUpload("STORY-1_part_001.mp3", MP3_HEADER),
                        partUpload("STORY-1_part_1.mp3", MP3_HEADER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIO_PARTS"))
                .andExpect(jsonPath("$.message").value("Duplicate part number 1."));
    }

    @Test
    void missingSequenceNumberIsRejectedByDefault() throws Exception {
        mockMvc.perform(processRequest("STORY-1", imageUpload("bg.png", PNG_HEADER),
                        partUpload("STORY-1_part_001.mp3", MP3_HEADER),
                        partUpload("STORY-1_part_003.mp3", MP3_HEADER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing part number 2."));
    }

    @Test
    void partsForAnotherStoryAreRejected() throws Exception {
        mockMvc.perform(processRequest("STORY-1", imageUpload("bg.png", PNG_HEADER),
                        partUpload("STORY-2_part_001.mp3", MP3_HEADER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIO_PARTS"));
    }

    @Test
    void tooManyPartsIsRejected() throws Exception {
        MockMultipartFile[] parts = new MockMultipartFile[201];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = partUpload(String.format("STORY-1_part_%03d.mp3", i + 1), MP3_HEADER);
        }
        mockMvc.perform(processRequest("STORY-1", imageUpload("bg.png", PNG_HEADER), parts))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Too many audio parts. The maximum is 200."));
    }

    @Test
    void invalidRenderOptionsAreBadRequest() throws Exception {
        MockHttpServletRequestBuilder odd = processRequest("STORY-1", imageUpload("bg.png", PNG_HEADER),
                partUpload("STORY-1_part_001.mp3", MP3_HEADER))
                .param("videoWidth", "1921");
        mockMvc.perform(odd)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RENDER_OPTIONS"));

        MockHttpServletRequestBuilder badFit = processRequest("STORY-1", imageUpload("bg.png", PNG_HEADER),
                partUpload("STORY-1_part_001.mp3", MP3_HEADER))
                .param("imageFit", "stretch");
        mockMvc.perform(badFit).andExpect(status().isBadRequest());

        MockHttpServletRequestBuilder badBitrate = processRequest("STORY-1", imageUpload("bg.png", PNG_HEADER),
                partUpload("STORY-1_part_001.mp3", MP3_HEADER))
                .param("audioBitrate", "192000; rm -rf /");
        mockMvc.perform(badBitrate).andExpect(status().isBadRequest());

        MockHttpServletRequestBuilder badFormat = processRequest("STORY-1", imageUpload("bg.png", PNG_HEADER),
                partUpload("STORY-1_part_001.mp3", MP3_HEADER))
                .param("outputFormat", "webm");
        mockMvc.perform(badFormat).andExpect(status().isBadRequest());
    }

    @Test
    void nonImageUploadIsUnprocessable() throws Exception {
        mockMvc.perform(processRequest("STORY-1",
                        imageUpload("bg.png", "definitely not an image".getBytes(StandardCharsets.UTF_8)),
                        partUpload("STORY-1_part_001.mp3", MP3_HEADER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
    }

    @Test
    void executableDisguisedAsAudioIsUnprocessable() throws Exception {
        byte[] windowsExecutable = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};
        mockMvc.perform(processRequest("STORY-1", imageUpload("bg.png", PNG_HEADER),
                        partUpload("STORY-1_part_001.mp3", windowsExecutable)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIO"));
    }

    @Test
    void oversizedFileIsPayloadTooLarge() throws Exception {
        byte[] tooBig = new byte[2 * 1024 * 1024];
        System.arraycopy(PNG_HEADER, 0, tooBig, 0, PNG_HEADER.length);
        mockMvc.perform(processRequest("STORY-1", imageUpload("bg.png", tooBig),
                        partUpload("STORY-1_part_001.mp3", MP3_HEADER)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    // Errors, correlation and downloads

    @Test
    void errorResponsesEchoValidRequestIdHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/media/STORY-1/audio")
                        .header("X-API-Key", API_KEY)
                        .header("X-Request-Id", "make-run-20261024-0001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.requestId").value("make-run-20261024-0001"))
                .andExpect(jsonPath("$.code").value("OUTPUT_NOT_FOUND"))
                .andReturn();
        assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo("make-run-20261024-0001");
    }

    @Test
    void malformedRequestIdIsReplaced() throws Exception {
        mockMvc.perform(get("/api/v1/media/STORY-1/audio")
                        .header("X-API-Key", API_KEY)
                        .header("X-Request-Id", "bad id\r\nInjected: yes"))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.not("bad id\r\nInjected: yes")));
    }

    @Test
    void missingOutputsReturnNotFoundWithoutPaths() throws Exception {
        mockMvc.perform(get("/api/v1/media/UNKNOWN-STORY/video").header("X-API-Key", API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OUTPUT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/"))));
    }

    @Test
    void invalidStoryIdOnDownloadIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/media/a.b/audio").header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STORY_ID"));
    }

    @Test
    void deleteUnknownStoryIsNotFound() throws Exception {
        mockMvc.perform(delete("/api/v1/media/UNKNOWN-STORY").header("X-API-Key", API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void unsupportedContentTypeIsRejectedCleanly() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/media/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }
}
