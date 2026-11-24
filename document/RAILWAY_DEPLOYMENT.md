# Railway deployment

This document describes the manual steps. Nothing in this repository creates a Railway project, changes an existing one, or purchases a plan.

**Status:** deployed to a private test service in Railway project `crorso-media-processor-test`, environment `test`, at `https://media-processor-test.up.railway.app`. Verified on that service: health, authentication (401 for missing and wrong keys), validation (400), a 3-part render, downloads, a corrupt-part failure that left the previous output intact (422, byte-identical afterwards), oversize rejection (413 for a 27 MB image), deletion, a 10-minute 60-part render, and loss of outputs on redeploy (see [Storage](#storage-and-persistence)). Not yet verified on Railway: the processing timeout path and the Make.com integration.

**Railway platform note:** Railway rejects the Dockerfile `VOLUME` instruction ("use Railway Volumes"). The Dockerfile therefore does not declare one; attach a volume in Railway if you need persistence.

## What gets deployed

- One service built from the repository root `Dockerfile`.
- Runtime: Temurin JRE 21 on Ubuntu 24.04 with FFmpeg and FFprobe from apt, running as a non-root user.
- Health check: `GET /actuator/health` (set in `railway.json`).
- Start command: the image entrypoint, `java -jar`. Railway's `PORT` is used automatically.

## Before you start

1. A Railway account with a plan that allows the resources below.
2. A strong API key. Generate one locally:
   ```bash
   openssl rand -hex 32
   ```
   Keep it for the service variables step. Do not commit it.
3. Decide on persistence (see [Storage](#storage-and-persistence)).

## Steps

1. **Create the service.** In the Railway dashboard, choose *New project → Deploy from GitHub repo* (or *Empty project → Add service → GitHub repo*) and select this repository. The root directory is the repository root.
2. **Config as code.** Railway reads `railway.json` from the root: Dockerfile builder, health check `/actuator/health`, restart on failure. Confirm these in *Settings → Build* and *Settings → Deploy*.
3. **Variables.** In *Variables*, add at least:

   | Name | Value |
   |---|---|
   | `API_KEY` | the key you generated (32+ characters) |
   | `SPRING_PROFILES_ACTIVE` | `prod` (also baked into the image; setting it explicitly is clear) |

   Optional, usually left at defaults: `MAX_FILE_SIZE_MB`, `MAX_UPLOAD_SIZE_MB`, `MAX_AUDIO_PARTS`, `MAX_CONCURRENT_JOBS`, `PROCESSING_TIMEOUT_SECONDS`, `OUTPUT_RETENTION_HOURS`, `LOG_LEVEL`. Full list in [CONFIGURATION.md](CONFIGURATION.md).

   Do **not** set `SERVER_PORT` or `PORT`; Railway provides `PORT`.

4. **Public domain.** In *Settings → Networking*, generate a Railway domain or attach your own. Then set `PUBLIC_BASE_URL` to `https://<that-domain>` so download links in responses use the public origin. Without it, links are derived from the request, which works behind Railway's proxy but is less explicit.
5. **Deploy.** Trigger a deploy (or push). Watch the build log for `ffmpeg` installation and the start log for `Started MediaProcessorApplication`.
6. **Verify.**
   ```bash
   curl -sS https://<domain>/actuator/health
   # {"status":"UP"}
   ```
   Then run the processing smoke test from [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md) against the public URL with `X-API-Key`. Use a short test story.

### Startup failure: API key

If the deploy crashes with `API_KEY must be set when running with the prod profile` or `... at least 32 characters`, add or correct the `API_KEY` variable and redeploy. The key value is never printed.

## Storage and persistence

**Local container storage on Railway is not guaranteed to persist.** Outputs written to `/data/output` live in the container filesystem. They are lost on:

- every redeploy,
- restarts that move the container,
- scaling events.

Verified on the test service: outputs were served before a redeploy and returned `404` after it.

Options:

1. **Accept ephemeral outputs (simplest).** Make.com downloads and stores the files in Google Drive immediately after each successful run. Only the in-flight story depends on the local copy. If a story must be re-downloaded after a redeploy, re-run the render step (narration can be reused from Drive without repeating TTS).
2. **Attach a Railway volume.** In the service, add a volume mounted at `/data/output`. Outputs then survive redeploys of the same service. Check Railway's current volume pricing and single-instance limits before relying on this; confirm with Railway's documentation for your plan.
3. **Object storage (future).** Implement `MediaStorage` against S3-compatible storage. This requires a paid account and is not part of the first version.

Whichever option you choose, Make.com must download results before `OUTPUT_RETENTION_HOURS` expires and must not assume a redeploy kept them.

## Resources

Measured on the Railway test service (8 vCPU / 8 GB limits):

| Story | Parts | Processing | Total request | Output |
|---|---|---|---|---|
| 6 s | 3 | 3.3 s | 4.9 s | MP3 145 KB, MP4 162 KB |
| 10 min | 60 × 10 s | 83.5 s | 110 s | MP3 14.4 MB, MP4 15.9 MB |

Peak memory for the 10-minute run was 2.01 GB, and the JVM kept about 1.97 GB afterwards. Peak CPU was 8.7 vCPU, because libx264 uses every core. Concurrent jobs will compete for CPU, so keep `MAX_CONCURRENT_JOBS` low unless the plan has more cores.

Make's HTTP timeout must exceed the total request time for the longest story you expect. The 10-minute story took 110 s, and longer stories scale roughly linearly.

Guidance for sizing:

- **Memory.** The JVM uses up to 60% of the container limit (`MaxRAMPercentage=60`). FFmpeg runs as a separate process and uses its own memory. Give the service at least 1 GB; 2 GB is a comfortable starting point for `MAX_CONCURRENT_JOBS=2`.
- **CPU.** libx264 uses all available cores by default. More vCPU shortens renders.
- **Disk.** Each job uses about 4× its input size in temporary space, plus the outputs kept for the retention period.
- **Concurrency.** Keep `MAX_CONCURRENT_JOBS` at or below what the CPU and memory allow. Excess requests receive 503 `SERVICE_BUSY`.

## Logs and correlation

Railway's log stream contains one line per processed story with the `requestId`, part count, sizes and durations. Errors include the same `requestId`, which Make.com receives in the error body and can record in Google Sheets for investigation.

## Redeploys and rollbacks

Redeploying replaces the container. In-flight requests are cut off; Make.com should treat that as a failed run and retry (see [MAKE_INTEGRATION.md](MAKE_INTEGRATION.md#retries-and-idempotency)). The service handles SIGTERM with a 30 s graceful shutdown.

## Changing the API key

1. Generate a new key.
2. Update `API_KEY` in Railway and redeploy.
3. Update the header value in every Make.com scenario that calls the service. Requests with the old key fail with 401 immediately after the redeploy.

## Costs and infrastructure to confirm

- Railway service runtime for the chosen memory and CPU.
- Egress for downloads (the MP4 is typically the largest transfer).
- Volume, only if you choose option 2 above.
- Nothing in this service calls a paid media API.
