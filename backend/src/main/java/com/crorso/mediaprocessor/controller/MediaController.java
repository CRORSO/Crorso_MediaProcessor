package com.crorso.mediaprocessor.controller;

import com.crorso.mediaprocessor.config.MediaProperties;
import com.crorso.mediaprocessor.dto.ProcessMediaResponse;
import com.crorso.mediaprocessor.dto.RenderOptions;
import com.crorso.mediaprocessor.dto.StagedPartResponse;
import com.crorso.mediaprocessor.dto.StagedPartsResponse;
import com.crorso.mediaprocessor.security.RequestIdFilter;
import com.crorso.mediaprocessor.service.MediaOutputService;
import com.crorso.mediaprocessor.service.MediaProcessingService;
import com.crorso.mediaprocessor.service.PartStagingService;
import com.crorso.mediaprocessor.service.ProcessingCommand;
import com.crorso.mediaprocessor.service.ProcessingResult;
import com.crorso.mediaprocessor.service.RenderOptionsResolver;
import com.crorso.mediaprocessor.service.StagedPart;
import com.crorso.mediaprocessor.service.storage.MediaKind;
import com.crorso.mediaprocessor.service.storage.StoredMedia;
import com.crorso.mediaprocessor.util.AudioPartParser;
import com.crorso.mediaprocessor.util.StoryIdValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/v1/media")
@Tag(name = "Media", description = "Merge narration MP3 parts and render an MP4 from a still image. Requires X-API-Key.")
public class MediaController {

    private final MediaProperties properties;
    private final RenderOptionsResolver optionsResolver;
    private final MediaProcessingService processingService;
    private final MediaOutputService outputService;
    private final PartStagingService partStagingService;

    public MediaController(MediaProperties properties, RenderOptionsResolver optionsResolver,
                           MediaProcessingService processingService, MediaOutputService outputService,
                           PartStagingService partStagingService) {
        this.properties = properties;
        this.optionsResolver = optionsResolver;
        this.processingService = processingService;
        this.outputService = outputService;
        this.partStagingService = partStagingService;
    }

    @PostMapping(value = "/{storyId}/parts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Stage one narration part",
            description = "Stores one MP3 under its part number. The file is validated on arrival. Sending the same "
                    + "partNumber again replaces that part. Stage every part, then call POST /process without audioParts.")
    public StagedPartResponse stagePart(
            HttpServletRequest request,
            @PathVariable String storyId,
            @RequestParam("partNumber") int partNumber,
            @RequestParam("audioPart") MultipartFile audioPart) {
        StagedPart staged = partStagingService.stage(storyId, partNumber, audioPart);
        return new StagedPartResponse(staged.storyId(), staged.partNumber(), staged.filename(),
                staged.sizeBytes(), staged.durationSeconds(), "STAGED", RequestIdFilter.currentRequestId(request));
    }

    @GetMapping(value = "/{storyId}/parts", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List staged part numbers",
            description = "Lists the part numbers currently staged for a story, so callers can skip chunks that already exist.")
    public StagedPartsResponse listParts(HttpServletRequest request, @PathVariable String storyId) {
        String validated = StoryIdValidator.validate(storyId);
        return new StagedPartsResponse(validated, partStagingService.stagedPartNumbers(validated),
                RequestIdFilter.currentRequestId(request));
    }

    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Process a story",
            description = "Synchronously merges the audioParts in part-number order and renders the MP4. "
                    + "Returns only after both outputs are verified. Re-processing a story replaces its outputs.")
    public ProcessMediaResponse process(
            HttpServletRequest request,
            @RequestParam("storyId") String storyId,
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "audioParts", required = false) List<MultipartFile> audioParts,
            @RequestParam(value = "outputFormat", required = false) String outputFormat,
            @RequestParam(value = "videoWidth", required = false) Integer videoWidth,
            @RequestParam(value = "videoHeight", required = false) Integer videoHeight,
            @RequestParam(value = "frameRate", required = false) Integer frameRate,
            @RequestParam(value = "imageFit", required = false) String imageFit,
            @RequestParam(value = "audioBitrate", required = false) String audioBitrate,
            @RequestParam(value = "backgroundColor", required = false) String backgroundColor,
            @RequestParam(value = "allowGaps", defaultValue = "false") boolean allowGaps) {

        String validatedStoryId = StoryIdValidator.validate(storyId);
        RenderOptions options = optionsResolver.resolve(outputFormat, videoWidth, videoHeight, frameRate,
                imageFit, audioBitrate, backgroundColor);
        // Uploaded parts are ordered here. When none are uploaded, the processor uses the parts staged for the story.
        List<MultipartFile> orderedParts = audioParts == null || audioParts.isEmpty()
                ? List.of()
                : AudioPartParser.order(audioParts, MultipartFile::getOriginalFilename,
                        validatedStoryId, allowGaps, properties.maxAudioParts());

        ProcessingResult result = processingService.process(
                new ProcessingCommand(validatedStoryId, image, orderedParts, options, allowGaps));

        String baseUrl = baseUrl(request);
        String audioUrl = baseUrl + "/api/v1/media/" + validatedStoryId + "/audio";
        String videoUrl = baseUrl + "/api/v1/media/" + validatedStoryId + "/video";

        return new ProcessMediaResponse(
                validatedStoryId,
                "COMPLETED",
                "Narration merged and video rendered. Download both files before the retention period ends.",
                result.audio().filename(),
                result.video().filename(),
                result.audio().sizeBytes(),
                result.video().sizeBytes(),
                result.audioDurationSeconds(),
                result.videoDurationSeconds(),
                result.processingDurationMs(),
                audioUrl,
                videoUrl,
                RequestIdFilter.currentRequestId(request));
    }

    @GetMapping("/{storyId}/audio")
    @Operation(summary = "Download the merged narration MP3")
    public ResponseEntity<Resource> downloadAudio(@PathVariable String storyId) {
        return download(storyId, MediaKind.AUDIO);
    }

    @GetMapping("/{storyId}/video")
    @Operation(summary = "Download the rendered MP4")
    public ResponseEntity<Resource> downloadVideo(@PathVariable String storyId) {
        return download(storyId, MediaKind.VIDEO);
    }

    @DeleteMapping("/{storyId}")
    @Operation(summary = "Delete a story's generated outputs")
    public ResponseEntity<Void> delete(@PathVariable String storyId) {
        outputService.deleteStory(storyId);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Resource> download(String storyId, MediaKind kind) {
        StoredMedia media = outputService.require(storyId, kind);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(media.contentType()))
                .contentLength(media.sizeBytes())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(media.filename()).build().toString())
                .body(new FileSystemResource(media.path()));
    }

    private String baseUrl(HttpServletRequest request) {
        String configured = properties.publicBaseUrl();
        if (configured != null && !configured.isBlank()) {
            String trimmed = configured;
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed;
        }
        return ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
    }
}
