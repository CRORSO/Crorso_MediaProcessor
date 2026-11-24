package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.config.MediaProperties;
import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;
import com.crorso.mediaprocessor.service.MediaInfo.StreamInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Probes media with FFprobe and applies the acceptance rules for inputs. Validation is based on the
 * actual decoded container and codecs, never on the filename or the declared content type.
 */
@Service
public class FfprobeService {

    private static final List<String> SUPPORTED_IMAGE_CODECS = List.of("png", "mjpeg", "webp", "bmp");

    private final CommandRunner runner;
    private final MediaProperties properties;
    private final ObjectMapper objectMapper;

    public FfprobeService(CommandRunner runner, MediaProperties properties, ObjectMapper objectMapper) {
        this.runner = runner;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Probes a file, failing with {@code invalidCode} when FFprobe cannot read it.
     */
    public MediaInfo probe(Path file, ErrorCode invalidCode, String description) {
        CommandRunner.Result result = runner.run(List.of(
                properties.ffprobePath(), "-v", "error", "-print_format", "json", "-show_format", "-show_streams",
                file.toAbsolutePath().toString()), file.getParent());

        if (!result.success()) {
            throw new MediaException(invalidCode, description + " could not be read as a valid media file.");
        }
        try {
            return parse(result.stdout());
        } catch (JsonProcessingException e) {
            throw new MediaException(invalidCode, description + " could not be read as a valid media file.", e);
        }
    }

    /** Requires a readable MP3 with an audio stream and a positive duration. */
    public MediaInfo requireMp3(Path file, String description) {
        MediaInfo info = probe(file, ErrorCode.INVALID_AUDIO, description);
        boolean isMp3 = info.formatName().toLowerCase(Locale.ROOT).contains("mp3");
        StreamInfo audio = info.firstStream("audio").orElse(null);
        if (!isMp3 || audio == null || !"mp3".equals(audio.codecName()) || info.durationSeconds() <= 0) {
            throw new MediaException(ErrorCode.INVALID_AUDIO, description + " must be a non-empty MP3 file.");
        }
        return info;
    }

    /** Requires a still image in one of the supported codecs with non-zero dimensions. */
    public MediaInfo requireImage(Path file) {
        MediaInfo info = probe(file, ErrorCode.INVALID_IMAGE, "The image");
        StreamInfo video = info.firstStream("video").orElse(null);
        if (video == null || !SUPPORTED_IMAGE_CODECS.contains(video.codecName())
                || video.width() <= 0 || video.height() <= 0) {
            throw new MediaException(ErrorCode.INVALID_IMAGE, ErrorCode.INVALID_IMAGE.defaultMessage());
        }
        return info;
    }

    private MediaInfo parse(String json) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode format = root.path("format");
        double duration = parseDouble(format.path("duration").asText(null));

        List<StreamInfo> streams = new ArrayList<>();
        for (JsonNode node : root.path("streams")) {
            streams.add(new StreamInfo(
                    node.path("codec_type").asText(""),
                    node.path("codec_name").asText(""),
                    parseInt(node.path("sample_rate").asText(null)),
                    node.path("channels").asInt(0),
                    node.path("width").asInt(0),
                    node.path("height").asInt(0)));
        }
        return new MediaInfo(format.path("format_name").asText(""), duration, streams);
    }

    private static double parseDouble(String value) {
        try {
            return value == null ? 0 : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
