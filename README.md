# Crorso Media Processor

A small, stateless Spring Boot service. It takes one background image and numbered narration MP3 parts for a story, and returns one merged MP3 and one YouTube-ready MP4. It uses FFmpeg and FFprobe directly. There is no database, no AI API and no paid media API. Make.com is the intended caller.

## Status

| Area | State |
|---|---|
| Application code (`backend/`) | Implemented |
| Unit and API tests | Passing (`./gradlew :backend:test`) |
| Integration tests with real FFmpeg | Passing; generate their own synthetic media |
| End-to-end run of the packaged jar over HTTP | Verified locally on Windows with FFmpeg 8.1 and JDK 21 |
| Docker image build and Compose run | Written, **not verified** (Docker daemon was not running) |
| Railway deployment | Configuration prepared, **not deployed** |
| Make.com scenario | Contract documented, **not built or run** |

## Quick start

```bash
./gradlew build                      # compile, test, package
export API_KEY="$(openssl rand -hex 32)"
./gradlew :backend:bootRun
curl http://localhost:8080/actuator/health
```

Requires JDK 21 (Gradle provisions one if missing) and FFmpeg/FFprobe on `PATH`. Full instructions: [document/LOCAL_DEVELOPMENT.md](document/LOCAL_DEVELOPMENT.md).

## API at a glance

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/media/process` | Multipart: `storyId`, `image`, repeated `audioParts`, plus optional render options |
| GET | `/api/v1/media/{storyId}/audio` | Download the merged MP3 |
| GET | `/api/v1/media/{storyId}/video` | Download the MP4 |
| POST | `/api/v1/media/{storyId}/short` | Multipart: `image`, optional `startSeconds`, `durationSeconds` (5-60). Renders a vertical 1080x1920 clip for YouTube Shorts |
| GET | `/api/v1/media/{storyId}/short` | Download the Short MP4 |
| DELETE | `/api/v1/media/{storyId}` | Delete a story's outputs |
| GET | `/actuator/health` | Health (public) |

Every `/api/**` call needs `X-API-Key: <key>`. Parts are ordered by the number in their filename (`STORY-001_part_010.mp3` follows `..._part_002.mp3`).

## Outputs

For story `STORY-001`:

```
MEDIA_OUTPUT_DIR/
└── STORY-001/
    ├── STORY-001_full.mp3   merged narration (one encode pass when several parts are joined)
    └── STORY-001.mp4        H.264 + AAC, 1920x1080, 24 fps, yuv420p, faststart
```

Outputs are kept for `OUTPUT_RETENTION_HOURS` (default 24) and are replaced on re-processing.

## Documentation

| Document | Contents |
|---|---|
| [API.md](document/API.md) | Endpoints, fields, ordering rules, responses, error codes |
| [ARCHITECTURE.md](document/ARCHITECTURE.md) | Components, processing pipeline, concurrency, storage model |
| [CONFIGURATION.md](document/CONFIGURATION.md) | Every environment variable, defaults and consistency rules |
| [LOCAL_DEVELOPMENT.md](document/LOCAL_DEVELOPMENT.md) | Build, test, run, curl examples, Docker Compose |
| [RAILWAY_DEPLOYMENT.md](document/RAILWAY_DEPLOYMENT.md) | Manual deployment steps, persistence limits, resource sizing |
| [MAKE_INTEGRATION.md](document/MAKE_INTEGRATION.md) | Make.com module-by-module setup, Sheets S/T rules, retries |
| [SECURITY.md](document/SECURITY.md) | Authentication, secrets, validation, limits, known gaps |
| [TROUBLESHOOTING.md](document/TROUBLESHOOTING.md) | Errors by code, log search by request ID |

## Repository layout

```
Dockerfile, docker-compose.yml, railway.json   container and platform config
.env.example                                    placeholders only; copy to .env
settings.gradle, build.gradle, gradlew*         build (Gradle 8.14 wrapper)
backend/                                        Spring Boot application and tests
document/                                       documentation
```

## Secrets

`API_KEY` is the only secret. It comes from the environment. Nothing secret is committed or baked into the image. `.env` is git-ignored.
