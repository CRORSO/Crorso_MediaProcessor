# Local development

## Requirements

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | Gradle provisions one automatically if none is found (needs network). Verified with Temurin 21.0.12 |
| Gradle | not needed | Use the wrapper: `./gradlew` (Linux/macOS) or `gradlew.bat` (Windows) |
| FFmpeg and FFprobe | 6.x or later | Must be on `PATH` or set with `FFMPEG_PATH` / `FFPROBE_PATH`. Verified with 8.1 (gyan.dev essentials build on Windows) |
| Docker | 24+ with Compose v2 | Optional. Needed only for container runs |

Check FFmpeg:

```bash
ffmpeg -version
ffprobe -version
```

Windows PowerShell with a custom location:

```powershell
$env:FFMPEG_PATH = "C:\tools\ffmpeg\bin\ffmpeg.exe"
$env:FFPROBE_PATH = "C:\tools\ffmpeg\bin\ffprobe.exe"
```

## Build and test

```bash
./gradlew build                 # compiles, runs all tests, produces backend/build/libs/crorso-media-processor.jar
./gradlew :backend:test         # tests only
./gradlew :backend:bootJar      # executable jar only
```

Test reports: `backend/build/reports/tests/test/index.html`. Per-class XML: `backend/build/test-results/test/`.

### What the tests cover

- **Unit:** story ID rules, filename sanitising, numeric part ordering (including 2 before 10), duplicate and missing numbers, cross-story rejection, file signatures and executable detection, FFmpeg argument construction, concat list escaping, process timeout and failure handling, admission control and per-story locking, storage isolation, traversal and symlink refusal, retention.
- **API (MockMvc):** API-key enforcement, JSON error format and request IDs, validation paths, size limits, download and delete authorization, content types.
- **Integration (real FFmpeg):** synthetic MP3 and PNG fixtures are generated in each run. These tests verify the produced MP3 and MP4 with FFprobe, including ordering, mixed-format joins, duration accuracy across many parts, gap handling, `contain` dimensions, republish, rejection of corrupt audio without replacing outputs, and deletion. They are skipped when FFmpeg or FFprobe is not runnable; the Gradle report shows them as skipped.

## Run the service

```bash
export API_KEY="$(openssl rand -hex 32)"     # any string of 32+ characters
export MEDIA_TEMP_DIR=./data/temp
export MEDIA_OUTPUT_DIR=./data/output
./gradlew :backend:bootRun
```

Windows PowerShell:

```powershell
$env:API_KEY = "local-dev-key-at-least-32-characters-long"
./gradlew :backend:bootRun
```

Without `SPRING_PROFILES_ACTIVE=prod` the default profile does not enforce key length, which is acceptable locally only. A missing key still rejects every `/api/**` request.

Check health: `curl http://localhost:8080/actuator/health` returns `{"status":"UP"}`. `DOWN` means FFmpeg or FFprobe cannot be run.

## Smoke test with curl

Create synthetic inputs (needs FFmpeg):

```bash
mkdir -p smoke && cd smoke
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "color=c=0x2E4057:s=1024x768" -frames:v 1 bg.png
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=440:duration=2" -c:a libmp3lame -b:a 128k STORY-DEMO_part_001.mp3
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=660:duration=3" -c:a libmp3lame -b:a 128k STORY-DEMO_part_002.mp3
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=880:duration=1" -c:a libmp3lame -b:a 128k STORY-DEMO_part_010.mp3
```

Process (part 10 is not contiguous, so `allowGaps=true` is needed here):

```bash
curl -sS -X POST http://localhost:8080/api/v1/media/process \
  -H "X-API-Key: $API_KEY" \
  -F storyId=STORY-DEMO \
  -F image=@bg.png \
  -F audioParts=@STORY-DEMO_part_010.mp3 \
  -F audioParts=@STORY-DEMO_part_001.mp3 \
  -F audioParts=@STORY-DEMO_part_002.mp3 \
  -F allowGaps=true \
  -F imageFit=cover
```

Download both outputs:

```bash
curl -sS -H "X-API-Key: $API_KEY" -o STORY-DEMO_full.mp3 http://localhost:8080/api/v1/media/STORY-DEMO/audio
curl -sS -H "X-API-Key: $API_KEY" -o STORY-DEMO.mp4       http://localhost:8080/api/v1/media/STORY-DEMO/video
ffprobe -v error -show_entries format=duration -of csv=p=0 STORY-DEMO.mp4
```

Delete:

```bash
curl -sS -X DELETE -H "X-API-Key: $API_KEY" http://localhost:8080/api/v1/media/STORY-DEMO -w "%{http_code}\n"
```

Windows note: `curl` is built in on Windows 10/11. Use `curl.exe` in PowerShell, because `curl` there is an alias for `Invoke-WebRequest`.

## Docker Compose

```bash
cp .env.example .env          # then edit API_KEY
docker compose up --build
curl http://localhost:8080/actuator/health
```

Outputs persist in the named volume `media-output`. Remove it with `docker compose down -v` only when you intend to delete outputs.

## Project layout

```
backend/src/main/java/com/crorso/mediaprocessor/   application code
backend/src/main/resources/application.yml         all settings, env-driven
backend/src/test/java/...                          unit, API and integration tests
document/                                          this documentation set
```

## Troubleshooting local runs

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
