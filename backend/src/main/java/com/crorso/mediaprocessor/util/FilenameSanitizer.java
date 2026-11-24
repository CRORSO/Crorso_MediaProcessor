package com.crorso.mediaprocessor.util;

/**
 * Reduces an uploaded filename to a safe basename of ASCII letters, digits, dots, underscores and hyphens.
 * Client-supplied names are never used as filesystem paths; this exists for parsing and log output.
 */
public final class FilenameSanitizer {

    private static final int MAX_LENGTH = 120;

    private FilenameSanitizer() {
    }

    public static String sanitize(String original) {
        if (original == null) {
            return "unnamed";
        }
        String basename = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1);
        String cleaned = basename.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "");
        if (cleaned.isEmpty()) {
            return "unnamed";
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(cleaned.length() - MAX_LENGTH) : cleaned;
    }
}
