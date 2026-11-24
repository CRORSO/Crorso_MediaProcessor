package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.config.MediaProperties;
import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandRunnerTest {

    @TempDir
    Path workDir;

    private final CommandRunner runner = new CommandRunner(properties());

    private static MediaProperties properties() {
        return new MediaProperties(null, "t", "o", "ffmpeg", "ffprobe", 1, 1, 1, 1, 1, 900, 1, 2, 2, 1,
                "128k", "black", 0, null);
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @Test
    void capturesStdoutOfSuccessfulProcess() {
        CommandRunner.Result result = runner.run(List.of(javaBinary(), "-version"), workDir);
        assertThat(result.success()).isTrue();
        assertThat(result.stdout() + result.stderrTail()).contains("version");
    }

    @Test
    void reportsNonZeroExitWithoutThrowing() {
        CommandRunner.Result result = runner.run(List.of(javaBinary(), "-XX:+NoSuchFlagForCrorsoTests"), workDir);
        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void requireSuccessMapsFailureToSafeException() {
        CommandRunner.Result failed = new CommandRunner.Result(1, "", "/secret/internal/path boom");

        assertThatThrownBy(() -> CommandRunner.requireSuccess(failed, "rendering the video"))
                .isInstanceOfSatisfying(MediaException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.PROCESSING_FAILED);
                    assertThat(e.getMessage()).doesNotContain("secret").doesNotContain("/");
                });
    }

    @Test
    void killsProcessThatExceedsTimeout() {
        List<String> command = List.of(javaBinary(), "-cp", System.getProperty("java.class.path"),
                SleepForever.class.getName());

        long started = System.nanoTime();
        assertThatThrownBy(() -> runner.run(command, workDir, Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(MediaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PROCESSING_TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void missingExecutableIsReportedAsUnavailable() {
        assertThatThrownBy(() -> runner.run(List.of("definitely-not-a-real-binary-crorso"), workDir))
                .isInstanceOfSatisfying(MediaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FFMPEG_UNAVAILABLE));
    }
}
