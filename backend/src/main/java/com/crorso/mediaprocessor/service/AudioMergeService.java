package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.config.MediaProperties;
import com.crorso.mediaprocessor.service.MediaInfo.StreamInfo;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

import static com.crorso.mediaprocessor.service.FfmpegCommands.TARGET_CHANNELS;
import static com.crorso.mediaprocessor.service.FfmpegCommands.TARGET_SAMPLE_RATE;

/**
 * Joins ordered narration parts into one MP3.
 *
 * <ul>
 *   <li>A single part already at the target format is remuxed without re-encoding.</li>
 *   <li>Several parts are joined in one decode-and-encode pass. Stream-copying MP3 segments leaves encoder
 *       padding at every boundary (measured at about 40 ms per part), which accumulates into audible extra
 *       silence. A single pass avoids that. No silence is inserted deliberately.</li>
 * </ul>
 */
@Service
public class AudioMergeService {

    private final CommandRunner runner;
    private final String ffmpegPath;

    public AudioMergeService(CommandRunner runner, MediaProperties properties) {
        this.runner = runner;
        this.ffmpegPath = properties.ffmpegPath();
    }

    /**
     * @param parts        validated MP3 files, already in narration order
     * @param infos        probe results for {@code parts}, same order
     * @param audioBitrate bitrate for the encoded output
     * @param workDir      job workspace; the join runs with this as its working directory
     * @param output       destination MP3, which must not exist yet
     */
    public void merge(List<Path> parts, List<MediaInfo> infos, String audioBitrate, Path workDir, Path output) {
        if (parts.size() == 1 && !needsNormalization(infos.get(0))) {
            CommandRunner.requireSuccess(
                    runner.run(FfmpegCommands.copySingle(ffmpegPath, parts.get(0), output), workDir),
                    "copying the narration");
            return;
        }
        CommandRunner.requireSuccess(
                runner.run(FfmpegCommands.joinParts(ffmpegPath, parts, workDir, output, audioBitrate), workDir),
                "joining narration parts");
    }

    static boolean needsNormalization(MediaInfo info) {
        StreamInfo audio = info.firstStream("audio").orElseThrow();
        return audio.sampleRate() != TARGET_SAMPLE_RATE || audio.channels() != TARGET_CHANNELS;
    }
}
