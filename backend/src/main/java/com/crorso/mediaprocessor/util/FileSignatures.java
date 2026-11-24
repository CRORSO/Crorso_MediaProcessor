package com.crorso.mediaprocessor.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Identifies uploads by their leading bytes rather than their filename or declared content type.
 * Used as a first gate before FFprobe, and to reject executables and scripts outright.
 */
public final class FileSignatures {

    public enum Kind { MP3, PNG, JPEG, WEBP, BMP, UNKNOWN }

    private static final int HEADER_LENGTH = 16;

    private FileSignatures() {
    }

    public static Kind detect(Path file) throws IOException {
        byte[] h = readHeader(file);
        if (startsWith(h, 'I', 'D', '3') || isMpegLayer3FrameSync(h)) {
            return Kind.MP3;
        }
        if (startsWith(h, 0x89, 'P', 'N', 'G')) {
            return Kind.PNG;
        }
        if (startsWith(h, 0xFF, 0xD8, 0xFF)) {
            return Kind.JPEG;
        }
        if (startsWith(h, 'R', 'I', 'F', 'F') && matchesAt(h, 8, 'W', 'E', 'B', 'P')) {
            return Kind.WEBP;
        }
        if (startsWith(h, 'B', 'M')) {
            return Kind.BMP;
        }
        return Kind.UNKNOWN;
    }

    /** True for native executables, scripts and archives, which must never be accepted as media. */
    public static boolean isExecutableOrArchive(Path file) throws IOException {
        byte[] h = readHeader(file);
        return startsWith(h, 'M', 'Z')                              // Windows PE
                || startsWith(h, 0x7F, 'E', 'L', 'F')                // Linux ELF
                || startsWith(h, 0xCF, 0xFA, 0xED, 0xFE)             // Mach-O
                || startsWith(h, 0xFE, 0xED, 0xFA, 0xCE)             // Mach-O (big endian)
                || startsWith(h, '#', '!')                           // shell / script shebang
                || startsWith(h, 'P', 'K', 0x03, 0x04);              // ZIP / JAR / Office containers
    }

    private static boolean isMpegLayer3FrameSync(byte[] h) {
        return h.length >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0 && (h[1] & 0x06) == 0x02;
    }

    private static boolean startsWith(byte[] data, int... expected) {
        return matchesAt(data, 0, expected);
    }

    private static boolean matchesAt(byte[] data, int offset, int... expected) {
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[offset + i] & 0xFF) != (expected[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readHeader(Path file) throws IOException {
        byte[] header = new byte[HEADER_LENGTH];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.readNBytes(header, 0, HEADER_LENGTH);
            return read == HEADER_LENGTH ? header : Arrays.copyOf(header, read);
        }
    }
}
