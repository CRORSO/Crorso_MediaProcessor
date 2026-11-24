package com.crorso.mediaprocessor.dto;

import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;

import java.util.Locale;

/**
 * How the still image is placed in the video frame.
 * {@code COVER} fills the frame and crops; {@code CONTAIN} keeps the whole image and pads with the background colour.
 */
public enum ImageFit {
    COVER, CONTAIN;

    public static ImageFit parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return COVER;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MediaException(ErrorCode.INVALID_RENDER_OPTIONS, "imageFit must be 'cover' or 'contain'.", e);
        }
    }
}
