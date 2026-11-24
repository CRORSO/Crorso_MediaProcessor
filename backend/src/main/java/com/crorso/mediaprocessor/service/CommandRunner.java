package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.config.MediaProperties;
import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs external processes from an argument list. There is never a shell involved, so arguments
 * cannot be interpreted as commands. Output is redirected to files inside the job workspace, which
 * avoids pipe-buffer deadlocks and keeps memory bounded. The process is killed on timeout.
 */
@Component
public class CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(CommandRunner.class);
    private static final int MAX_STDOUT_BYTES = 1024 * 1024;
    private static final int MAX_STDERR_TAIL_BYTES = 8 * 1024;

    private final Duration defaultTimeout;

    public CommandRunner(MediaProperties properties) {
        this.defaultTimeout = Duration.ofSeconds(properties.processingTimeoutSeconds());
    }

    public Result run(List<String> command, Path workDir) {
        return run(command, workDir, defaultTimeout);
    }

    public Result run(List<String> command, Path workDir, Duration timeout) {
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new MediaException(ErrorCode.PROCESSING_FAILED, "The processing workspace could not be created.", e);
        }
        Path stdoutFile = workDir.resolve("cmd-" + UUID.randomUUID() + ".out");
        Path stderrFile = workDir.resolve("cmd-" + UUID.randomUUID() + ".err");
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectOutput(stdoutFile.toFile())
                    .redirectError(stderrFile.toFile())
                    .start();
            process.getOutputStream().close();

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                log.warn("Process {} exceeded {} seconds and was terminated", command.get(0), timeout.toSeconds());
                throw new MediaException(ErrorCode.PROCESSING_TIMEOUT, ErrorCode.PROCESSING_TIMEOUT.defaultMessage());
            }
            return new Result(process.exitValue(), readStdout(stdoutFile), readStderrTail(stderrFile));
        } catch (IOException e) {
            throw new MediaException(ErrorCode.FFMPEG_UNAVAILABLE, ErrorCode.FFMPEG_UNAVAILABLE.defaultMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaException(ErrorCode.PROCESSING_FAILED, "Media processing was interrupted.", e);
        } finally {
            deleteQuietly(stdoutFile);
            deleteQuietly(stderrFile);
        }
    }

    /**
     * Converts a non-zero exit into a safe processing failure. FFmpeg's stderr tail is logged
     * internally only, never returned to the caller.
     */
    public static void requireSuccess(Result result, String operation) {
        if (!result.success()) {
            log.warn("FFmpeg exited with code {} while {}: {}", result.exitCode(), operation, result.stderrTail());
            throw new MediaException(ErrorCode.PROCESSING_FAILED,
                    "Media processing failed while " + operation + ". Check the request inputs and retry.");
        }
    }

    private static String readStdout(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(MAX_STDOUT_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String readStderrTail(Path file) {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            long size = channel.size();
            long start = Math.max(0, size - MAX_STDERR_TAIL_BYTES);
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // read until the tail is full or the file ends
            }
            return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Could not delete temporary command output");
        }
    }

    public record Result(int exitCode, String stdout, String stderrTail) {

        public boolean success() {
            return exitCode == 0;
        }
    }
}
