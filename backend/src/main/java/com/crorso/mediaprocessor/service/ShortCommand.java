package com.crorso.mediaprocessor.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * A validated request for a vertical clip. {@code startSeconds} and {@code durationSeconds} are already
 * within the allowed range; the clip is cut from the story's stored narration.
 */
public record ShortCommand(String storyId, MultipartFile image, double startSeconds, double durationSeconds) {
}
