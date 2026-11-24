package com.crorso.mediaprocessor.service.storage;

import java.nio.file.Path;

/**
 * A verified output that is available for download.
 *
 * @param path      location of the bytes; callers stream from this path and never load it into memory
 * @param filename  download filename
 * @param sizeBytes file size
 */
public record StoredMedia(Path path, String filename, String contentType, long sizeBytes) {
}
