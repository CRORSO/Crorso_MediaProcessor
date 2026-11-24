package com.crorso.mediaprocessor.service;

import java.util.List;
import java.util.Optional;

/**
 * The subset of FFprobe output the service relies on.
 *
 * @param formatName      container name reported by FFprobe, e.g. {@code mp3} or {@code mov,mp4,m4a,3gp,3g2,mj2}
 * @param durationSeconds container duration, or 0 when unknown
 * @param streams         streams in file order
 */
public record MediaInfo(String formatName, double durationSeconds, List<StreamInfo> streams) {

    public Optional<StreamInfo> firstStream(String codecType) {
        return streams.stream().filter(s -> codecType.equals(s.codecType())).findFirst();
    }

    /**
     * @param codecType  {@code audio}, {@code video} or another FFprobe type
     * @param codecName  e.g. {@code mp3}, {@code aac}, {@code h264}, {@code png}
     * @param sampleRate audio sample rate in Hz, 0 for non-audio
     * @param channels   audio channel count, 0 for non-audio
     * @param width      video width, 0 for non-video
     * @param height     video height, 0 for non-video
     */
    public record StreamInfo(String codecType, String codecName, int sampleRate, int channels, int width, int height) {
    }
}
