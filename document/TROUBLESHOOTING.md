# Troubleshooting

Start with the `requestId` from the failing response. Search the service logs for it: every log line for a request carries it. If the caller did not send `X-Request-Id`, the response body and `X-Request-Id` header contain the generated one.

## Startup

| Symptom | Likely cause | Fix |
|---|---|---|
| `API_KEY must be set when running with the prod profile` | `prod` without a key | Set `API_KEY` |
| `API_KEY must be at least 32 characters in the prod profile` | Key too short | Generate with `openssl rand -hex 32` |
| `MEDIA_OUTPUT_DIR must not be inside MEDIA_TEMP_DIR` | Overlapping paths | Point the two variables at separate directories |
| `Failed to bind properties under 'media.*'` | Missing or out-of-range value | Check [CONFIGURATION.md](CONFIGURATION.md) defaults |
| Port already in use | Another process on 8080 | Set `SERVER_PORT` locally (or `HOST_PORT` for Compose) |
| `Could not create test directory` / permission errors on `/data` | Directory not writable by the runtime user | Check the volume's ownership; the image creates `/data` owned by `media` |

## Health check is DOWN

`/actuator/health` returns `{"status":"DOWN"}` when FFmpeg or FFprobe cannot be run.

1. Locally: run `ffmpeg -version` and `ffprobe -version` in the same shell that starts the service. On Windows, check that the PATH change applied (restart the terminal after installing FFmpeg).
2. Set `FFMPEG_PATH` / `FFPROBE_PATH` to absolute paths if the command is not on PATH.
3. In Docker, the image installs FFmpeg with apt. A DOWN status there suggests a broken image build; rebuild with `docker compose build --no-cache`.

## Authentication

| Symptom | Cause |
|---|---|
| `401 UNAUTHORIZED` on every call | `API_KEY` unset on the server, or the caller sends a different value |
| Works locally, `401` on Railway | Railway variable not set, or set on a different service or environment |
| Works with `X-API-Key`, fails with `Authorization` | Use exactly `Bearer <key>`, one space, no quotes |
| Download links return `401` in a browser | Expected. Downloads need the header; use curl or Make's HTTP module with the key |

## Input errors (400 / 413 / 422)

| `code` | Typical cause | Fix |
|---|---|---|
| `INVALID_STORY_ID` | Spaces, dots, slashes, or more than 64 characters | Use letters, digits, `_`, `-` |
| `INVALID_AUDIO_PARTS` with "must match" | File not named `<storyId>_part_<n>.mp3` | Rename in the TTS step |
| `... does not belong to storyId` | Part from another story attached | Check the loop variable |
| `Duplicate part number N` | Two chunks numbered the same | Check numbering in the TTS step |
| `Missing part number N` | A chunk was not generated or not attached | Attach it, or set `allowGaps=true` on purpose |
| `Too many audio parts` | More than `MAX_AUDIO_PARTS` | Raise the limit and `MAX_MULTIPART_PART_COUNT`, or split the story |
| `Invalid rendering options` | Odd width/height, out-of-range value, bad bitrate or colour | Check the option against [API.md](API.md) |
| `PAYLOAD_TOO_LARGE` | A file exceeds `MAX_FILE_SIZE_MB`, or the request exceeds `MAX_UPLOAD_SIZE_MB` | Compress the image or raise the limit |
| `INVALID_IMAGE` | Not PNG, JPEG, WEBP or BMP (for example GIF, TIFF or HEIC) | Convert to PNG |
| `INVALID_AUDIO` | MP3 is truncated, not an MP3, or is an executable | Re-generate the chunk; check it with `ffprobe` |
| `AUDIO_TOO_LONG` | Combined narration over the limit | Raise `MAX_AUDIO_DURATION_SECONDS` if the server can handle it |
| `UNSUPPORTED_MEDIA_TYPE` | Sent as JSON or `x-www-form-urlencoded` | Use multipart form-data |

## Processing errors (5xx)

| `code` | Meaning | Where to look |
|---|---|---|
| `SERVICE_BUSY` (503) | `MAX_CONCURRENT_JOBS` in use | Retry with back-off; raise the limit only if CPU and memory allow |
| `STORY_BUSY` (409) | Same story already running | Avoid overlapping runs in Make |
| `FFMPEG_UNAVAILABLE` (503) | FFmpeg could not start | Check install and `FFMPEG_PATH` |
| `INSUFFICIENT_STORAGE` (507) | Low disk | Free space on the temp/output volume or lower `MIN_FREE_DISK_MB` |
| `PROCESSING_TIMEOUT` (504) | An FFmpeg step ran past `PROCESSING_TIMEOUT_SECONDS` | Check the story length and host CPU; raise the timeout if the host is sized for it |
| `PROCESSING_FAILED` (500) | FFmpeg exited non-zero | Server log: search for `FFmpeg exited with code` under the `requestId` |
| `OUTPUT_VERIFICATION_FAILED` (500) | FFprobe did not confirm the output | Server log will show the failing check; nothing was published |
| `INTERNAL_ERROR` (500) | Unexpected exception | Server log: stack trace under the `requestId` |

## Reading the server log for a request

```bash
# Local
grep "<requestId>" run.log
# Railway: use the log search box with the requestId
```

Useful messages:

- `Processed story X for request Y: parts=..., inputBytes=..., audioSeconds=..., videoSeconds=..., elapsedMs=...`: success metrics.
- `FFmpeg exited with code N while <operation>: <stderr tail>`: the FFmpeg diagnostic. Only in logs.
- `Process ffmpeg exceeded N seconds and was terminated`: timeout.
- `Rejected unauthenticated POST /api/...`: key problem.

## Download problems

| Symptom | Cause |
|---|---|
| `404 OUTPUT_NOT_FOUND` after a successful run | Retention expired, the story was deleted, or the instance was redeployed without persistent storage. Re-run the render (reuse the chunks; do not re-run TTS) |
| Downloaded MP3 or MP4 is shorter than expected | Truncated transfer. Compare the byte count with `*SizeBytes` and retry |
| Windows: `Could not fully delete` or a rerun fails while downloading | A file is open by another process; Windows will not rename over it. Avoid downloading during a re-render, or re-run after the download finishes |

## Output quality

- Audio has a faint click or a very short gap at a part boundary: the parts were encoded with different settings, so the join is a fresh encode and the boundary sits where the source files end. Generating the TTS chunks at one fixed format (for example 44.1 kHz stereo MP3) avoids most of this. Multi-part narrations are always re-encoded once, so a single generation of loss is expected at the chosen `audioBitrate`.
- Output is longer than the sum of the parts by a few tens of milliseconds per part: MP3 decoder and encoder priming. This is within the verifier's allowance (50 ms per part). Larger differences fail verification with `OUTPUT_VERIFICATION_FAILED`.
- Video ends before the narration: should not happen, because `-shortest` ends the output when the audio ends. Verify with `ffprobe -show_entries format=duration`; if it differs, open an issue with the story's `requestId`.
- `contain` shows bars: expected. Choose `backgroundColor` to match the design.

## Compose and Docker

| Symptom | Fix |
|---|---|
| `API_KEY must be set` / `variable is not set` from Compose | Create `.env` from `.env.example` |
| `docker compose up` build fails at `gradlew` | Ensure line endings of `gradlew` are LF (`.gitattributes` or `git config core.autocrlf input`) |
| Healthcheck stays `starting` | First start can take up to 60 s; check `docker compose logs media-processor` |
| Container exits immediately | Read the log; usually the `prod` key check |

## Known limitations

- Processing is synchronous. Very long stories can exceed the Make.com HTTP timeout. See [MAKE_INTEGRATION.md](MAKE_INTEGRATION.md#long-stories-and-timeouts).
- Outputs are local files. They do not survive redeploys without a volume.
- Only MP4 output is available.
- Only one API key.

## Collecting information for a bug report

1. The failing `requestId` and the time of the request.
2. The request shape: fields, number of parts, part names (not content), image type and size.
3. The response body (it contains no secrets).
4. The relevant server log lines for that `requestId`.
5. `ffmpeg -version` from the environment where the service runs.
