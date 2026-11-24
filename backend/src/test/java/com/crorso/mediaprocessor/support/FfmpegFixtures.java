package com.crorso.mediaprocessor.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Generates tiny synthetic media with FFmpeg so integration tests need no external assets or services.
 * Also provides FFprobe helpers used to verify outputs independently of the application code.
 */
public final class FfmpegFixtures {

    private FfmpegFixtures() {
    }

    /** True only when both FFmpeg and FFprobe can be executed. Integration tests are skipped otherwise. */
    public static boolean isAvailable() {
        return canRun("ffmpeg", "-version") && canRun("ffprobe", "-version");
    }

    public static Path sineMp3(Path dir, String name, double seconds, int sampleRate, int channels) {
        Path out = dir.resolve(name);
        run("ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", String.format(Locale.ROOT, "sine=frequency=440:duration=%.3f:sample_rate=%d", seconds, sampleRate),
                "-ac", String.valueOf(channels), "-c:a", "libmp3lame", "-b:a", "128k", out.toString());
        return out;
    }

    public static Path solidPng(Path dir, String name, int width, int height) {
        Path out = dir.resolve(name);
        run("ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", String.format(Locale.ROOT, "color=c=0x1F4E79:s=%dx%d", width, height),
                "-frames:v", "1", out.toString());
        return out;
    }

    public static double duration(Path file) {
        return Double.parseDouble(probeLine(file, null, "format=duration"));
    }

    /** Returns FFprobe's CSV output for the selected stream, e.g. {@code probe(f, "v:0", "stream=codec_name,width,height")}. */
    public static String probe(Path file, String streamSelector, String entries) {
        return probeLine(file, streamSelector, entries);
    }

    private static String probeLine(Path file, String streamSelector, String entries) {
        List<String> command = new java.util.ArrayList<>(List.of("ffprobe", "-v", "error"));
        if (streamSelector != null) {
            command.addAll(List.of("-select_streams", streamSelector));
        }
        command.addAll(List.of("-show_entries", entries, "-of", "csv=p=0", file.toAbsolutePath().toString()));
        return run(command.toArray(String[]::new)).strip().lines().findFirst().orElse("");
    }

    private static boolean canRun(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("Fixture command failed: " + Arrays.toString(command) + "\n" + output);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Fixture command could not start: " + command[0], e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture command interrupted", e);
        }
    }

    public static Path writeBytes(Path dir, String name, byte[] bytes) throws IOException {
        return Files.write(dir.resolve(name), bytes);
    }
}
