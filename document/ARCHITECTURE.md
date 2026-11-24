# Architecture

## Purpose

Crorso Media Processor is a stateless HTTP service. A caller supplies one story ID, one background image and numbered narration MP3 parts. The service returns one merged MP3 and one MP4 with the image as a looping background. It has no database and calls no AI, TTS or paid media API. Its first consumer is an automated Tamil story pipeline in Make.com, but nothing in the service is language-specific.

## Components

```
HTTP (Spring MVC)
  RequestIdFilter ──► ApiKeyAuthenticationFilter ──► MediaController
                                                       │
            RenderOptionsResolver ◄────────────────────┤
            AudioPartParser (util)                     │
                                                       ▼
                                          MediaProcessingService ──► JobGate (slots + story locks)
                                           │        │         │
                                  FfprobeService  AudioMergeService  VideoRenderService
                                           │        │         │
                                           └──── CommandRunner (ProcessBuilder, timeouts) ──► ffmpeg / ffprobe
                                                       │
                                                OutputVerifier
                                                       │
                                  MediaStorage (LocalMediaStorage) ◄── OutputRetentionScheduler
                                    ├─ workspace: MEDIA_TEMP_DIR/job-<uuid>/   (deleted in finally)
                                    └─ outputs:   MEDIA_OUTPUT_DIR/<storyId>/  (atomic publish)
```

| Package | Responsibility |
|---|---|
| `controller` | HTTP mapping only. Validates identifiers and calls services |
| `security` | Request IDs; API-key check using SHA-256 digests and constant-time comparison |
| `exception` | Stable error codes, `MediaException`, `@RestControllerAdvice` mapping |
| `dto` | Immutable request/response records and render options |
| `util` | Story ID rules, filename sanitising, part ordering, file signature detection |
| `service` | Processing pipeline, FFmpeg command construction and execution, verification, admission control |
| `service.storage` | `MediaStorage` interface and local-disk implementation, retention |
| `config` | Typed settings, OpenAPI, production start-up check, health indicator |

## Processing pipeline

1. **Admission.** Estimate required disk space and refuse with 507 if short. Take a processing slot (503 if none free) and the story lock (409 if the story is busy).
2. **Workspace.** Create `MEDIA_TEMP_DIR/job-<uuid>/` with `input/` and `output/`. The UUID makes each job's files invisible to others.
3. **Ingest.** Stream each part to `input/part_NNN.mp3` and the image to `input/image.<ext>`. Check per-file size. Check magic bytes and reject executables and archives. Parts are checked before the image.
4. **Probe.** FFprobe confirms each part is an MP3 with an audio stream and positive duration, and the image is a supported still format with dimensions. The total narration length is compared with `MAX_AUDIO_DURATION_SECONDS`.
5. **Merge.** A single part already at 44.1 kHz stereo is remuxed without re-encoding. Otherwise all parts are joined in one FFmpeg run: each input is converted to 44.1 kHz stereo inside a `concat` filter graph, and the result is encoded once as MP3 at the requested bitrate. Joining by stream copy was tried first and rejected: each MP3 segment carries encoder padding (about 40 ms measured per part), which accumulated into extra silence, so 12 half-second parts came out 6.53 s long instead of 6.0 s. No silence is inserted deliberately.
6. **Verify audio.** FFprobe confirms MP3 format and that the duration matches the sum of parts within max(1 s, 2%) plus 50 ms per part, which allows for decoder and encoder priming.
7. **Render.** `ffmpeg -loop 1` on the image, narration as audio, `-shortest` so video length equals narration. `cover` scales up and crops; `contain` scales down and pads.
8. **Verify video.** FFprobe confirms H.264 at the requested size, AAC audio, MP4/MOV container, and matching duration.
9. **Publish.** Each verified file is copied to a hidden staging file in its story directory and renamed over the target with an atomic move. Outputs from an earlier run are replaced only at this point.
10. **Cleanup.** The workspace is deleted in `finally`, whatever the outcome.

Nothing is published until both outputs have passed verification. A failure at any earlier step leaves the previously published outputs untouched.

## Concurrency

- `MAX_CONCURRENT_JOBS` bounds FFmpeg processes (fair semaphore). Extra requests are refused with 503 rather than queued, so callers can retry with backoff.
- One job or delete per story at a time (409 otherwise).
- Downloads take no lock. On Linux, a rename over an open file is safe for readers that already have it open. On Windows, the rename can fail while a download is in progress; this affects only local Windows development.

## Storage model

- Stateless by design. Nothing needed to serve a request lives outside `MEDIA_OUTPUT_DIR`.
- Outputs survive restarts only if that directory is on persistent storage. On Railway's default container filesystem they are lost on redeploy. See [RAILWAY_DEPLOYMENT.md](RAILWAY_DEPLOYMENT.md).
- `MediaStorage` is the seam for object storage. A future implementation would upload in `publish`, return a signed or streamed location from `find`, and delete remotely in `deleteStory`. The pipeline would not change.

## Why synchronous

The pipeline is short for typical stories (measured 2.5 s for 12 parts totalling 6 s of narration, rendered to 1080p, on a development workstation; longer narrations take proportionally longer). A synchronous API keeps Make.com scenarios simple and needs no queue or database. The trade-offs and a future asynchronous job design are in [MAKE_INTEGRATION.md](MAKE_INTEGRATION.md#long-stories-and-timeouts).

## Security boundaries

- Authentication happens before any body is read by a controller.
- Client-supplied names never become filesystem paths. Story IDs are validated against a strict pattern, and every resolved path is checked to stay inside its root and to not be a symbolic link.
- FFmpeg is started with an argument list, never a shell.
- Raw FFmpeg output is logged server-side only.

See [SECURITY.md](SECURITY.md).

## Technology choices

- Java 21, Spring Boot 3.5, Gradle wrapper 8.14, Jackson 2 (bundled).
- springdoc-openapi 2.8 for the API description.
- FFmpeg and FFprobe as external executables, found on `PATH` or via `FFMPEG_PATH`/`FFPROBE_PATH`.
- No Python, database, message broker, Redis or cloud SDK.
