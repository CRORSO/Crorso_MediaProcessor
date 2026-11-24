package com.crorso.mediaprocessor.util;

import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;

import java.util.regex.Pattern;

/**
 * Story IDs become directory names and appear in filenames, so they are restricted to a
 * conservative alphabet. Dots and slashes are rejected, which rules out path traversal.
 */
public final class StoryIdValidator {

    private static final Pattern STORY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private StoryIdValidator() {
    }

    public static String validate(String storyId) {
        if (storyId == null || !STORY_ID.matcher(storyId).matches()) {
            throw new MediaException(ErrorCode.INVALID_STORY_ID, ErrorCode.INVALID_STORY_ID.defaultMessage());
        }
        return storyId;
    }
}
