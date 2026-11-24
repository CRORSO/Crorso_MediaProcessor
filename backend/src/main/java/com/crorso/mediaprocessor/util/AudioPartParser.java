package com.crorso.mediaprocessor.util;

import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orders narration parts by the number embedded in their filename.
 *
 * <p>Expected names: {@code <storyId>_part_<number>.mp3}, for example {@code STORY-001_part_010.mp3}.
 * Numbers are compared as integers, so part 2 precedes part 10. The multipart field order is ignored.
 * Duplicate numbers are always rejected. Numbering must start at 1 and be contiguous unless
 * {@code allowGaps} is set.
 */
public final class AudioPartParser {

    private static final Pattern PART_NAME = Pattern.compile(
            "(?<story>[A-Za-z0-9][A-Za-z0-9_-]{0,63})_part_(?<number>\\d{1,4})\\.mp3",
            Pattern.CASE_INSENSITIVE);

    private AudioPartParser() {
    }

    public static <T> List<T> order(List<T> items, Function<T, String> filenameOf, String storyId,
                                    boolean allowGaps, int maxParts) {
        if (items == null || items.isEmpty()) {
            throw new MediaException(ErrorCode.INVALID_AUDIO_PARTS, "At least one audioParts file is required.");
        }
        if (items.size() > maxParts) {
            throw new MediaException(ErrorCode.INVALID_AUDIO_PARTS,
                    "Too many audio parts. The maximum is " + maxParts + ".");
        }

        Map<Integer, T> byNumber = new TreeMap<>();
        for (T item : items) {
            String name = FilenameSanitizer.sanitize(filenameOf.apply(item));
            Matcher matcher = PART_NAME.matcher(name);
            if (!matcher.matches()) {
                throw new MediaException(ErrorCode.INVALID_AUDIO_PARTS,
                        "File name '" + name + "' must match <storyId>_part_<number>.mp3.");
            }
            if (!storyId.equals(matcher.group("story"))) {
                throw new MediaException(ErrorCode.INVALID_AUDIO_PARTS,
                        "File name '" + name + "' does not belong to storyId " + storyId + ".");
            }
            int number = Integer.parseInt(matcher.group("number"));
            if (number < 1) {
                throw new MediaException(ErrorCode.INVALID_AUDIO_PARTS, "Part numbers start at 1.");
            }
            if (byNumber.putIfAbsent(number, item) != null) {
                throw new MediaException(ErrorCode.INVALID_AUDIO_PARTS, "Duplicate part number " + number + ".");
            }
        }

        if (!allowGaps) {
            int expected = 1;
            for (int number : byNumber.keySet()) {
                if (number != expected) {
                    throw new MediaException(ErrorCode.INVALID_AUDIO_PARTS, "Missing part number " + expected + ".");
                }
                expected++;
            }
        }
        return new ArrayList<>(byNumber.values());
    }
}
