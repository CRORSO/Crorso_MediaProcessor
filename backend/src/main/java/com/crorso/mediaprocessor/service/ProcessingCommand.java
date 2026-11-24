package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.dto.RenderOptions;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * A validated processing request. When {@code audioParts} is empty, the parts previously staged for the
 * story are used. Otherwise {@code audioParts} must already be in narration order.
 */
public record ProcessingCommand(
        String storyId,
        MultipartFile image,
        List<MultipartFile> audioParts,
        RenderOptions options,
        boolean allowGaps) {

    public boolean usesStagedParts() {
        return audioParts == null || audioParts.isEmpty();
    }

    public long uploadedBytes() {
        long total = image.getSize();
        for (MultipartFile part : audioParts) {
            total += part.getSize();
        }
        return total;
    }
}
