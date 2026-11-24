package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.config.MediaProperties;
import com.crorso.mediaprocessor.dto.ImageFit;
import com.crorso.mediaprocessor.dto.OutputFormat;
import com.crorso.mediaprocessor.dto.RenderOptions;
import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;
import com.crorso.mediaprocessor.security.RequestIdFilter;
import com.crorso.mediaprocessor.service.storage.MediaKind;
import com.crorso.mediaprocessor.service.storage.MediaStorage;
import com.crorso.mediaprocessor.service.storage.StoredMedia;
import com.crorso.mediaprocessor.util.FileSignatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs one story from its narration parts to published outputs.
 *
 * <p>Parts come either from the request itself or from parts staged earlier for the story. Each job gets its
 * own workspace. Outputs are published only after verification, and the workspace is removed in a
 * {@code finally} block whether the job succeeds or fails. Staged parts are removed only after a successful
 * publish, so a failed render can be retried without regenerating audio.
 */
@Service
public class MediaProcessingService {

    private static final Logger log = LoggerFactory.getLogger(MediaProcessingService.class);
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final int TEMP_EXPANSION_FACTOR = 4;

    /** Vertical frame for YouTube Shorts. */
    static final int SHORT_WIDTH = 1080;
    static final int SHORT_HEIGHT = 1920;
    public static final double SHORT_MIN_SECONDS = 5.0;
    /** YouTube's classic Shorts limit; staying at or under it keeps the clip eligible everywhere. */
    public static final double SHORT_MAX_SECONDS = 60.0;

    private final MediaProperties properties;
    private final MediaStorage storage;
    private final JobGate jobGate;
    private final FfprobeService ffprobe;
    private final AudioMergeService audioMerge;
    private final VideoRenderService videoRender;
    private final OutputVerifier verifier;
    private final PartStagingService partStaging;

    public MediaProcessingService(MediaProperties properties, MediaStorage storage, JobGate jobGate,
                                  FfprobeService ffprobe, AudioMergeService audioMerge,
                                  VideoRenderService videoRender, OutputVerifier verifier,
                                  PartStagingService partStaging) {
        this.properties = properties;
        this.storage = storage;
        this.jobGate = jobGate;
        this.ffprobe = ffprobe;
        this.audioMerge = audioMerge;
        this.videoRender = videoRender;
        this.verifier = verifier;
        this.partStaging = partStaging;
    }

    public ProcessingResult process(ProcessingCommand command) {
        long started = System.nanoTime();
        String storyId = command.storyId();

        try (JobGate.Ticket ignored = jobGate.acquire(storyId)) {
            List<Path> staged = command.usesStagedParts()
                    ? partStaging.orderedForProcessing(storyId, command.allowGaps(), properties.maxAudioParts())
                    : List.of();
            long inputBytes = command.usesStagedParts()
                    ? command.image().getSize() + sizeOf(staged)
                    : command.uploadedBytes();
            requireFreeSpace(inputBytes);

            Path workspace = storage.createWorkspace();
            try {
                return runPipeline(command, staged, workspace, started);
            } finally {
                storage.deleteWorkspace(workspace);
            }
        } catch (IOException e) {
            throw new MediaException(ErrorCode.PROCESSING_FAILED,
                    "Media files could not be read or written during processing.", e);
        }
    }

    /**
     * Cuts a vertical clip from the narration already published for the story. The story must have been
     * processed first and not yet expired. The clip is verified before it replaces any earlier one.
     */
    public ShortResult createShort(ShortCommand command) {
        long started = System.nanoTime();
        String storyId = command.storyId();

        try (JobGate.Ticket ignored = jobGate.acquire(storyId)) {
            StoredMedia narration = storage.find(storyId, MediaKind.AUDIO)
                    .orElseThrow(() -> new MediaException(ErrorCode.OUTPUT_NOT_FOUND,
                            "No merged audio exists for this story. Process the story first, "
                                    + "and process it again if it has expired."));
            requireFreeSpace(command.image().getSize());

            Path workspace = storage.createWorkspace();
            try {
                return runShortPipeline(command, narration, workspace, started);
            } finally {
                storage.deleteWorkspace(workspace);
            }
        } catch (IOException e) {
            throw new MediaException(ErrorCode.PROCESSING_FAILED,
                    "Media files could not be read or written during processing.", e);
        }
    }

    private ShortResult runShortPipeline(ShortCommand command, StoredMedia narration, Path workspace, long started)
            throws IOException {
        String storyId = command.storyId();
        Path inputDir = Files.createDirectories(workspace.resolve("input"));
        Path outputDir = Files.createDirectories(workspace.resolve("output"));

        Path image = saveImage(command.image(), inputDir);
        ffprobe.requireImage(image);
        MediaInfo narrationInfo = ffprobe.requireMp3(narration.path(), "The stored narration");

        double narrationSeconds = narrationInfo.durationSeconds();
        double start = command.startSeconds();
        double available = narrationSeconds - start;
        if (available < 1.0) {
            throw new MediaException(ErrorCode.INVALID_RENDER_OPTIONS,
                    "startSeconds is beyond the end of the narration, which is "
                            + round(narrationSeconds) + " seconds long.");
        }
        // A narration shorter than the requested clip simply gives a shorter clip.
        double clipSeconds = Math.min(command.durationSeconds(), available);

        RenderOptions options = new RenderOptions(SHORT_WIDTH, SHORT_HEIGHT, properties.defaultFrameRate(),
                ImageFit.COVER, properties.defaultAudioBitrate(), properties.defaultBackgroundColor(),
                OutputFormat.MP4);

        Path clip = outputDir.resolve("short.mp4");
        videoRender.renderShort(image, narration.path(), options, start, clipSeconds, workspace, clip);
        double measuredSeconds = verifier.verifyVideo(clip, clipSeconds, options);

        StoredMedia published = storage.publish(storyId, MediaKind.SHORT, clip);

        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        log.info("Rendered short for story {} request {}: start={}, seconds={}, bytes={}, elapsedMs={}",
                storyId, MDC.get(RequestIdFilter.MDC_KEY), round(start), round(measuredSeconds),
                published.sizeBytes(), elapsedMs);

        return new ShortResult(storyId, published, start, measuredSeconds, elapsedMs);
    }

    private ProcessingResult runPipeline(ProcessingCommand command, List<Path> staged, Path workspace, long started)
            throws IOException {
        String storyId = command.storyId();
        Path inputDir = Files.createDirectories(workspace.resolve("input"));
        Path outputDir = Files.createDirectories(workspace.resolve("output"));

        // Cheap content checks run for every upload before any FFprobe call.
        List<Path> parts = command.usesStagedParts()
                ? copyStagedParts(staged, inputDir)
                : saveAudioParts(command.audioParts(), inputDir);
        Path image = saveImage(command.image(), inputDir);

        List<MediaInfo> partInfos = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            partInfos.add(ffprobe.requireMp3(parts.get(i), "Audio part " + (i + 1)));
        }
        ffprobe.requireImage(image);

        double narrationSeconds = partInfos.stream().mapToDouble(MediaInfo::durationSeconds).sum();
        if (narrationSeconds > properties.maxAudioDurationSeconds()) {
            throw new MediaException(ErrorCode.AUDIO_TOO_LONG, ErrorCode.AUDIO_TOO_LONG.defaultMessage());
        }

        Path mergedAudio = outputDir.resolve("merged.mp3");
        audioMerge.merge(parts, partInfos, command.options().audioBitrate(), workspace, mergedAudio);
        double audioSeconds = verifier.verifyAudio(mergedAudio, narrationSeconds, parts.size());

        Path video = outputDir.resolve("video.mp4");
        videoRender.render(image, mergedAudio, command.options(), workspace, video);
        double videoSeconds = verifier.verifyVideo(video, audioSeconds, command.options());

        StoredMedia audioOutput = storage.publish(storyId, MediaKind.AUDIO, mergedAudio);
        StoredMedia videoOutput = storage.publish(storyId, MediaKind.VIDEO, video);
        if (command.usesStagedParts()) {
            storage.clearStagedParts(storyId);
        }

        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        log.info("Processed story {} for request {}: parts={}, staged={}, audioBytes={}, videoBytes={}, "
                        + "audioSeconds={}, videoSeconds={}, elapsedMs={}",
                storyId, MDC.get(RequestIdFilter.MDC_KEY), parts.size(), command.usesStagedParts(),
                audioOutput.sizeBytes(), videoOutput.sizeBytes(), round(audioSeconds), round(videoSeconds), elapsedMs);

        return new ProcessingResult(storyId, audioOutput, videoOutput, audioSeconds, videoSeconds, elapsedMs);
    }

    private Path saveImage(MultipartFile upload, Path inputDir) throws IOException {
        requireWithinFileLimit(upload.getSize(), "The image");
        Path staged = inputDir.resolve("image.upload");
        upload.transferTo(staged);

        if (FileSignatures.isExecutableOrArchive(staged)) {
            throw new MediaException(ErrorCode.INVALID_IMAGE, ErrorCode.INVALID_IMAGE.defaultMessage());
        }
        String extension = switch (FileSignatures.detect(staged)) {
            case PNG -> "png";
            case JPEG -> "jpg";
            case WEBP -> "webp";
            case BMP -> "bmp";
            default -> throw new MediaException(ErrorCode.INVALID_IMAGE, ErrorCode.INVALID_IMAGE.defaultMessage());
        };
        return Files.move(staged, inputDir.resolve("image." + extension));
    }

    private List<Path> saveAudioParts(List<MultipartFile> uploads, Path inputDir) throws IOException {
        List<Path> saved = new ArrayList<>(uploads.size());
        for (int i = 0; i < uploads.size(); i++) {
            String label = "Audio part " + (i + 1);
            requireWithinFileLimit(uploads.get(i).getSize(), label);
            Path target = inputDir.resolve(String.format(Locale.ROOT, "part_%03d.mp3", i + 1));
            uploads.get(i).transferTo(target);

            if (FileSignatures.isExecutableOrArchive(target) || FileSignatures.detect(target) != FileSignatures.Kind.MP3) {
                throw new MediaException(ErrorCode.INVALID_AUDIO, label + " must be an MP3 file.");
            }
            saved.add(target);
        }
        return saved;
    }

    /** Copies staged parts into the job workspace, so the staged originals survive a failed render. */
    private static List<Path> copyStagedParts(List<Path> staged, Path inputDir) throws IOException {
        List<Path> copied = new ArrayList<>(staged.size());
        for (int i = 0; i < staged.size(); i++) {
            Path target = inputDir.resolve(String.format(Locale.ROOT, "part_%03d.mp3", i + 1));
            Files.copy(staged.get(i), target, StandardCopyOption.REPLACE_EXISTING);
            copied.add(target);
        }
        return copied;
    }

    private void requireWithinFileLimit(long sizeBytes, String label) {
        if (sizeBytes > properties.maxFileSizeMb() * BYTES_PER_MB) {
            throw new MediaException(ErrorCode.PAYLOAD_TOO_LARGE,
                    label + " exceeds the maximum file size of " + properties.maxFileSizeMb() + " MB.");
        }
    }

    private void requireFreeSpace(long inputBytes) {
        long required = properties.minFreeDiskMb() * BYTES_PER_MB + TEMP_EXPANSION_FACTOR * inputBytes;
        if (storage.usableBytes() < required) {
            throw new MediaException(ErrorCode.INSUFFICIENT_STORAGE, ErrorCode.INSUFFICIENT_STORAGE.defaultMessage());
        }
    }

    private static long sizeOf(List<Path> files) throws IOException {
        long total = 0;
        for (Path file : files) {
            total += Files.size(file);
        }
        return total;
    }

    private static double round(double seconds) {
        return Math.round(seconds * 100.0) / 100.0;
    }
}
