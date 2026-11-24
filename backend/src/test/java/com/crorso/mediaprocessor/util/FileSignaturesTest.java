package com.crorso.mediaprocessor.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileSignaturesTest {

    @TempDir
    Path dir;

    @Test
    void detectsMp3ByIdTagOrFrameSync() throws IOException {
        assertThat(FileSignatures.detect(write("id3.mp3", "ID3\u0004\u0000\u0000rest".getBytes()))).isEqualTo(FileSignatures.Kind.MP3);
        assertThat(FileSignatures.detect(write("sync.mp3", new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00}))).isEqualTo(FileSignatures.Kind.MP3);
    }

    @Test
    void detectsImageFormatsByMagicBytes() throws IOException {
        assertThat(FileSignatures.detect(write("a.png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})))
                .isEqualTo(FileSignatures.Kind.PNG);
        assertThat(FileSignatures.detect(write("a.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0})))
                .isEqualTo(FileSignatures.Kind.JPEG);
        assertThat(FileSignatures.detect(write("a.webp", "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".getBytes())))
                .isEqualTo(FileSignatures.Kind.WEBP);
    }

    @Test
    void reportsUnknownForPlainText() throws IOException {
        assertThat(FileSignatures.detect(write("notes.mp3", "hello world, this is not audio".getBytes())))
                .isEqualTo(FileSignatures.Kind.UNKNOWN);
    }

    @Test
    void flagsExecutablesAndArchivesEvenWithMediaExtensions() throws IOException {
        assertThat(FileSignatures.isExecutableOrArchive(write("evil.mp3", new byte[]{'M', 'Z', (byte) 0x90, 0}))).isTrue();
        assertThat(FileSignatures.isExecutableOrArchive(write("elf.mp3", new byte[]{0x7F, 'E', 'L', 'F', 2, 1}))).isTrue();
        assertThat(FileSignatures.isExecutableOrArchive(write("script.png", "#!/bin/sh\necho hi".getBytes()))).isTrue();
        assertThat(FileSignatures.isExecutableOrArchive(write("bundle.jpg", new byte[]{'P', 'K', 3, 4, 0, 0}))).isTrue();
    }

    @Test
    void doesNotFlagGenuineMedia() throws IOException {
        assertThat(FileSignatures.isExecutableOrArchive(write("ok.mp3", "ID3\u0004\u0000".getBytes()))).isFalse();
        assertThat(FileSignatures.isExecutableOrArchive(write("ok.png", new byte[]{(byte) 0x89, 'P', 'N', 'G'}))).isFalse();
    }

    @Test
    void handlesEmptyFiles() throws IOException {
        Path empty = write("empty.mp3", new byte[0]);
        assertThat(FileSignatures.detect(empty)).isEqualTo(FileSignatures.Kind.UNKNOWN);
        assertThat(FileSignatures.isExecutableOrArchive(empty)).isFalse();
    }

    private Path write(String name, byte[] content) throws IOException {
        return Files.write(dir.resolve(name), content);
    }
}
