# Configuration reference

Every setting is read from a generic environment variable. There are no application-specific prefixes. Defaults suit local development; production values are set on the platform.

## Variables

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | unset (Dockerfile sets `prod`) | `prod` refuses to start unless `API_KEY` is set and at least 32 characters |
| `PORT` | unset | Platform-assigned port. Takes precedence over `SERVER_PORT`. Railway sets this |
| `SERVER_PORT` | `8080` | Port for local runs |
| `API_KEY` | empty | Shared secret for `/api/**`. Empty means every `/api/**` request gets 401 |
| `MEDIA_TEMP_DIR` | `./data/temp` (image: `/data/temp`) | Per-job workspaces. Cleared of stale entries at startup |
| `MEDIA_OUTPUT_DIR` | `./data/output` (image: `/data/output`) | Published outputs, one folder per story |
| `FFMPEG_PATH` | `ffmpeg` | Path or command name of FFmpeg |
| `FFPROBE_PATH` | `ffprobe` | Path or command name of FFprobe |
| `MAX_UPLOAD_SIZE_MB` | `500` | Maximum size of one whole multipart request |
| `MAX_FILE_SIZE_MB` | `25` | Maximum size of one file (image or part) |
| `MAX_MULTIPART_PART_COUNT` | `250` | Tomcat limit on multipart parts per request. Keep above `MAX_AUDIO_PARTS` + 1 |
| `MAX_AUDIO_PARTS` | `200` | Maximum number of narration parts per story |
| `MAX_AUDIO_DURATION_SECONDS` | `7200` | Maximum combined narration length |
| `MAX_CONCURRENT_JOBS` | `2` | FFmpeg jobs running at once. Excess requests get 503 |
| `PROCESSING_TIMEOUT_SECONDS` | `900` | Timeout for each FFmpeg/FFprobe invocation |
| `OUTPUT_RETENTION_HOURS` | `24` | Outputs older than this are deleted by a scheduled sweep (hourly) |
| `DEFAULT_VIDEO_WIDTH` | `1920` | Default `videoWidth` |
| `DEFAULT_VIDEO_HEIGHT` | `1080` | Default `videoHeight` |
| `DEFAULT_FRAME_RATE` | `24` | Default `frameRate` |
| `DEFAULT_AUDIO_BITRATE` | `192k` | Default `audioBitrate` |
| `DEFAULT_BACKGROUND_COLOR` | `black` | Default `backgroundColor` for `contain` |
| `MIN_FREE_DISK_MB` | `512` | Requests are refused with 507 if free space drops below this plus 4x the input size |
| `PUBLIC_BASE_URL` | empty | Origin used in download URLs, e.g. `https://media.example.com`. Empty means derive from the request |
| `LOG_LEVEL` | `INFO` | Log level for the application packages |
| `OPENAPI_ENABLED` | `true` | Set `false` to hide `/v3/api-docs` and Swagger UI |

## Fixed behaviour (not configurable)

- Narration is joined at 44.1 kHz stereo. A single part already in that format is remuxed without re-encoding. Several parts are joined in one encode pass, which is what keeps boundary padding from accumulating (see [ARCHITECTURE.md](ARCHITECTURE.md#processing-pipeline)).
- Video codec H.264 (`libx264`, `-tune stillimage`, CRF 20, `yuv420p`), audio AAC, `+faststart`.
- Output filenames: `<storyId>_full.mp3` and `<storyId>.mp4`.

## Consistency rules

- `MAX_FILE_SIZE_MB` also sets Spring's per-file multipart limit; `MAX_UPLOAD_SIZE_MB` sets the per-request limit. Keep `MAX_UPLOAD_SIZE_MB` at least as large as the sum of the largest expected request.
- `MAX_MULTIPART_PART_COUNT` must be greater than `MAX_AUDIO_PARTS` plus the `image` field, or valid requests with many parts will be refused by the servlet container.
- `MEDIA_OUTPUT_DIR` must not be inside `MEDIA_TEMP_DIR`. The service refuses to start otherwise.

## Startup failures

| Symptom | Cause |
|---|---|
| `API_KEY must be set when running with the prod profile` | `prod` profile without a key |
| `API_KEY must be at least 32 characters in the prod profile` | Key too short |
| `MEDIA_OUTPUT_DIR must not be inside MEDIA_TEMP_DIR` | Directories overlap |
| `Binding to target ... media.*` validation errors | A numeric or required value is missing or out of range |

The key value is never printed in these messages.

## Railway-specific handling

- Railway injects `PORT`. The application binds to it automatically; do not set `SERVER_PORT` on Railway.
- Set secrets as service variables in the Railway dashboard. Do not commit them.
- Railway's proxy forwards `X-Forwarded-*` headers. `server.forward-headers-strategy=framework` makes generated download URLs use `https`.
