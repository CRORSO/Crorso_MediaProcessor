package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.config.MediaProperties;
import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;
import com.crorso.mediaprocessor.service.storage.MediaStorage;
import com.crorso.mediaprocessor.util.AudioPartParser;
import com.crorso.mediaprocessor.util.FileSignatures;
import com.crorso.mediaprocessor.util.StoryIdValidator;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stores narration chunks one at a time so a story's audio can be built up across several requests.
 *
 * <p>Each chunk is validated when it arrives. A chunk is stored under its part number, so sending the same
 * number again replaces it. This makes per-chunk retries safe.
 */
@Service
public class PartStagingService {

    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final MediaStorage storage;
    private final JobGate jobGate;
    private final FfprobeService ffprobe;
    private final MediaProperties properties;

    public PartStagingService(MediaStorage storage, JobGate jobGate, FfprobeService ffprobe, MediaProperties properties) {
        this.storage = storage;
        this.jobGate = jobGate;
        this.ffprobe = ffprobe;
        this.properties = properties;
    }

    public StagedPart stage(String storyId, int partNumber, MultipartFile upload) {
        String id = StoryIdValidator.validate(storyId);
        if (partNumber < 1 || partNumber > properties.maxAudioParts()) {
            throw new MediaException(ErrorCode.INVALID_AUDIO_PARTS,
                    "partNumber must be between 1 and " + properties.maxAudioParts() + ".");
        }
        if (upload.getSize() > properties.maxFileSizeMb() * BYTES_PER_MB) {
            throw new MediaException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "Audio part " + partNumber + " exceeds the maximum file size of " + properties.maxFileSizeMb() + " MB.");
        }

        try (JobGate.Ticket ignored = jobGate.lockStory(id)) {
            Path dir = storage.stagingDirectory(id);
            Path staging = dir.resolve(".upload-" + UUID.randomUUID() + ".tmp");
            Path target = dir.resolve(partFilename(id, partNumber));
            try {
                upload.transferTo(staging);
                if (FileSignatures.isExecutableOrArchive(staging)
                        || FileSignatures.detect(staging) != FileSignatures.Kind.MP3) {
                    throw new MediaException(ErrorCode.INVALID_AUDIO, "Audio part " + partNumber + " must be an MP3 file.");
                }
                MediaInfo info = ffprobe.requireMp3(staging, "Audio part " + partNumber);
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return new StagedPart(id, partNumber, target.getFileName().toString(),
                        Files.size(target), info.durationSeconds());
            } finally {
                Files.deleteIfExists(staging);
            }
        } catch (IOException e) {
            throw new MediaException(ErrorCode.PROCESSING_FAILED, "The audio part could not be stored.", e);
        }
    }

    public List<Integer> stagedPartNumbers(String storyId) {
        return listStaged(StoryIdValidator.validate(storyId)).stream().map(StagedFile::number).toList();
    }

    /**
     * Staged parts in narration order, ready for processing. Fails when nothing is staged, or when the
     * numbering has gaps and {@code allowGaps} is false.
     */
    public List<Path> orderedForProcessing(String storyId, boolean allowGaps, int maxParts) {
        String id = StoryIdValidator.validate(storyId);
        List<StagedFile> files = listStaged(id);
        if (files.isEmpty()) {
            throw new MediaException(ErrorCode.INVALID_AUDIO_PARTS,
                    "No audio parts are staged for this story. Stage them with POST /api/v1/media/" + id + "/parts first.");
        }
        return AudioPartParser.order(files, f -> f.path().getFileName().toString(), id, allowGaps, maxParts)
                .stream()
                .map(StagedFile::path)
                .toList();
    }

    private List<StagedFile> listStaged(String storyId) {
        Path dir = storage.stagingDirectory(storyId);
        Pattern name = Pattern.compile(Pattern.quote(storyId) + "_part_(\\d{1,4})\\.mp3");
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .map(path -> stagedFile(name, path))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(StagedFile::number))
                    .toList();
        } catch (IOException e) {
            throw new MediaException(ErrorCode.PROCESSING_FAILED, "Staged audio parts could not be listed.", e);
        }
    }

    private static StagedFile stagedFile(Pattern name, Path path) {
        Matcher matcher = name.matcher(path.getFileName().toString());
        return matcher.matches() ? new StagedFile(Integer.parseInt(matcher.group(1)), path) : null;
    }

    private static String partFilename(String storyId, int partNumber) {
        return String.format(Locale.ROOT, "%s_part_%03d.mp3", storyId, partNumber);
    }

    private record StagedFile(int number, Path path) {
    }
}
