# API reference

Base path: `/api/v1/media`. All `/api/**` endpoints require an API key (see [SECURITY.md](SECURITY.md)).

| Method | Path | Purpose | Auth |
|---|---|---|---|
| POST | `/api/v1/media/process` | Merge narration parts and render the MP4 for one story | Yes |
| GET | `/api/v1/media/{storyId}/audio` | Download the merged MP3 | Yes |
| GET | `/api/v1/media/{storyId}/video` | Download the MP4 | Yes |
| DELETE | `/api/v1/media/{storyId}` | Delete a story's outputs | Yes |
| GET | `/actuator/health` | Liveness and FFmpeg availability (no details) | No |
| GET | `/v3/api-docs`, `/swagger-ui.html` | OpenAPI document and UI (disable with `OPENAPI_ENABLED=false`) | No |

## Authentication

Send the key in either header:

```
X-API-Key: <API_KEY>
Authorization: Bearer <API_KEY>
```

Missing or wrong keys return `401` with the standard error body. The comparison is constant-time.

## POST /api/v1/media/process

`multipart/form-data`. Synchronous: the response is returned only after both outputs are verified.

### Fields

| Field | Required | Repeats | Default | Rules |
|---|---|---|---|---|
| `storyId` | yes | no | - | 1-64 characters: letters, digits, `_`, `-`; must start with a letter or digit |
| `image` | yes | no | - | PNG, JPEG, WEBP or BMP, at most `MAX_FILE_SIZE_MB`. Detected by content, not filename |
| `audioParts` | yes | yes | - | MP3 files named `<storyId>_part_<number>.mp3`. Number is 1-4 digits |
| `outputFormat` | no | no | `mp4` | Only `mp4` |
| `videoWidth` | no | no | `1920` | 16-7680, even |
| `videoHeight` | no | no | `1080` | 16-4320, even |
| `frameRate` | no | no | `24` | 1-60 |
| `imageFit` | no | no | `cover` | `cover` (fill and crop) or `contain` (whole image, padded) |
| `audioBitrate` | no | no | `192k` | Pattern `^[1-9][0-9]{1,2}k$`. Used for the joined narration (any multi-part story, or a single part not at 44.1 kHz stereo) |
| `backgroundColor` | no | no | `black` | Colour name or `#RRGGBB`. Used by `contain` |
| `allowGaps` | no | no | `false` | `true` permits non-contiguous part numbers |

### Part ordering

Parts are ordered by the integer in their filename, not by upload order and not lexically. `_part_2` precedes `_part_10`.

- Every filename must match `<storyId>_part_<number>.mp3` exactly, with the same `storyId`.
- Duplicate numbers are always rejected.
- Unless `allowGaps=true`, numbering must be contiguous starting at 1. Otherwise `Missing part number N.` is returned.
- Directory parts of a filename are stripped; other unsafe characters are replaced with `_`.

### Response: 200

```json
{
  "storyId": "STORY-001",
  "status": "COMPLETED",
  "message": "Narration merged and video rendered. Download both files before the retention period ends.",
  "mergedAudioFilename": "STORY-001_full.mp3",
  "videoFilename": "STORY-001.mp4",
  "mergedAudioSizeBytes": 147373,
  "videoSizeBytes": 163723,
  "audioDurationSeconds": 6.067755,
  "videoDurationSeconds": 6.067007,
  "processingDurationMs": 1773,
  "audioDownloadUrl": "https://media.example.com/api/v1/media/STORY-001/audio",
  "videoDownloadUrl": "https://media.example.com/api/v1/media/STORY-001/video",
  "requestId": "c21477ef-f501-423d-9ebc-2b526bc07c8c"
}
```

`status` is always `COMPLETED` on success. Failures return an error body, never a partial success.

Re-processing a story regenerates both outputs and replaces the previous ones. The response always describes the new files.

### Errors

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `INVALID_STORY_ID` | `storyId` fails the pattern |
| 400 | `INVALID_AUDIO_PARTS` | Bad filename, wrong story, duplicate, gap, too many parts, none supplied |
| 400 | `INVALID_RENDER_OPTIONS` | Out-of-range or malformed option, or odd dimensions |
| 400 | `INVALID_REQUEST` | Missing required field or malformed multipart body |
| 401 | `UNAUTHORIZED` | Missing or invalid API key |
| 409 | `STORY_BUSY` | Another job or delete is running for this `storyId` |
| 413 | `PAYLOAD_TOO_LARGE` | A file or the whole request exceeds limits |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Not `multipart/form-data` |
| 422 | `INVALID_IMAGE` | Image is not a supported image |
| 422 | `INVALID_AUDIO` | A part is not a readable, non-empty MP3 |
| 422 | `AUDIO_TOO_LONG` | Combined narration exceeds `MAX_AUDIO_DURATION_SECONDS` |
| 503 | `SERVICE_BUSY` | `MAX_CONCURRENT_JOBS` already running. Retry later |
| 503 | `FFMPEG_UNAVAILABLE` | FFmpeg or FFprobe cannot be executed |
| 507 | `INSUFFICIENT_STORAGE` | Not enough free disk for this job |
| 504 | `PROCESSING_TIMEOUT` | An FFmpeg step exceeded `PROCESSING_TIMEOUT_SECONDS` |
| 500 | `PROCESSING_FAILED` | FFmpeg failed on the inputs |
| 500 | `OUTPUT_VERIFICATION_FAILED` | Output did not pass FFprobe checks; nothing was published |
| 500 | `INTERNAL_ERROR` | Unexpected error. Quote the `requestId` when reporting |

## Staged workflow (recommended for automation)

Send narration one chunk per request, then render. This avoids sending a variable-length array of files in a single request, and each chunk is validated when it arrives.

### POST /api/v1/media/{storyId}/parts

`multipart/form-data`, one chunk per request.

| Field | Required | Notes |
|---|---|---|
| `partNumber` | yes | integer, 1 to `MAX_AUDIO_PARTS` |
| `audioPart` | yes | one MP3 file |

The file is stored as `<storyId>_part_NNN.mp3`. Sending the same `partNumber` again replaces that chunk, so retrying one chunk is safe.

Response (200):

```json
{
  "storyId": "STORY-001",
  "partNumber": 3,
  "filename": "STORY-001_part_003.mp3",
  "sizeBytes": 45120,
  "durationSeconds": 12.4,
  "status": "STAGED",
  "requestId": "..."
}
```

Errors: 400 `INVALID_AUDIO_PARTS` (part number out of range), 413 `PAYLOAD_TOO_LARGE`, 422 `INVALID_AUDIO` (not a readable MP3), 409 `STORY_BUSY` (a render for this story is running).

### GET /api/v1/media/{storyId}/parts

Lists the staged part numbers, in ascending order. Use it to skip chunks that already exist.

```json
{ "storyId": "STORY-001", "partNumbers": [1, 2, 3], "requestId": "..." }
```

### POST /api/v1/media/process (staged mode)

Omit `audioParts`. Send `storyId`, `image` and any render options. The service renders from the staged chunks in numeric order. Numbering must be contiguous from 1 unless `allowGaps=true`.

On success the staged chunks are deleted. On failure they are kept, so the render can be retried without regenerating audio.

## GET /api/v1/media/{storyId}/audio

Streams `audio/mpeg` with `Content-Disposition: attachment; filename="<storyId>_full.mp3"`. `Cache-Control: no-store`.

`404 OUTPUT_NOT_FOUND` if no output exists (never processed, deleted, or expired).

## GET /api/v1/media/{storyId}/video

Streams `video/mp4` with `Content-Disposition: attachment; filename="<storyId>.mp4"`.

## DELETE /api/v1/media/{storyId}

`204 No Content` when outputs were removed. `404 OUTPUT_NOT_FOUND` when there was nothing to delete. `409 STORY_BUSY` while a job for that story runs.

## Error body

Every error uses this shape:

```json
{
  "timestamp": "2026-10-24T03:47:46.097061400Z",
  "status": 404,
  "code": "OUTPUT_NOT_FOUND",
  "message": "No video exists for this story. Process the story again if it has expired.",
  "requestId": "6c42fbda-5b5f-416b-b06a-1f6d30fc9069"
}
```

Messages never contain filesystem paths, command lines or FFmpeg output. Those are written to the server log under the same `requestId`.

## Request correlation

Every response carries `X-Request-Id`. Send your own (8-64 characters, letters, digits, hyphens) to correlate with your workflow run. Invalid values are replaced with a generated UUID.

## Examples

See [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md) for local `curl` examples, [RAILWAY_DEPLOYMENT.md](RAILWAY_DEPLOYMENT.md) for the deployed endpoint, and [MAKE_INTEGRATION.md](MAKE_INTEGRATION.md) for Make.com.
