package com.crorso.mediaprocessor.dto;

import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;

import java.util.Locale;

/**
 * Requested output container. Only MP4 is produced in the first version; the parameter is kept so
 * other formats can be added without changing the request contract.
 */
public enum OutputFormat {
    MP4;

    public static OutputFormat parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return MP4;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MediaException(ErrorCode.INVALID_RENDER_OPTIONS, "outputFormat must be 'mp4'.", e);
        }
    }
}
