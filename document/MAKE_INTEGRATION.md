# Make.com integration

This service sits between the story pipeline and Google Drive. Make.com does everything else: it calls TTS, asks this service to render media, downloads the results, uploads them to Drive, and updates the Google Sheet.

**Status:** the contract below is implemented and tested against the service. A Make.com scenario has **not** been built or run as part of this work. Treat the steps as a specification to build from, and confirm each module's field names in your Make version.

## Scenario overview

```
[Sheets: row ready]
   → [TTS: N MP3 chunks per story, named STORY-ID_part_NNN.mp3]     (existing OpenAI step)
   → [HTTP: POST /api/v1/media/process]                              (this service)
   → [Router: success?]
        ├─ yes → [HTTP: GET audio] → [Drive: upload MP3] ─┐
        │        [HTTP: GET video] → [Drive: upload MP4] ─┤
        │                                                 └→ [Sheets: update S and T]
        └─ no  → [Sheets: write error status, leave S and T unchanged]
```

## Module 1: call the service

Module: **HTTP → Make a request**

| Setting | Value |
|---|---|
| URL | `https://<your-railway-domain>/api/v1/media/process` |
| Method | `POST` |
| Headers | `X-API-Key: {{your key}}` and `X-Request-Id: {{scenario run ID}}-{{row number}}` (optional) |
| Body type | `Multipart/form-data` |
| Parse response | Yes |
| Timeout | At least 300 s (see [Long stories and timeouts](#long-stories-and-timeouts)) |

Store the API key in a Make **connection** or a scenario variable marked secret. Do not hard-code it in the module.

### Form-data fields

| Key | Type | Value |
|---|---|---|
| `storyId` | Text | The story identifier, e.g. `STORY-001`. Letters, digits, `_`, `-` only |
| `image` | File | The generated background image. Attach the file data from the image module. PNG, JPEG, WEBP or BMP |
| `audioParts` | File (repeat) | One entry per TTS chunk, see below |
| `imageFit` | Text (optional) | `cover` (default) or `contain` |
| `allowGaps` | Text (optional) | Leave unset. Only set `true` if you deliberately skip part numbers |

### Attaching the MP3 chunks in order

The service orders parts by the number in each filename. You do not need to sort the array, but sorting it makes logs easier to read.

1. Make the TTS module produce each chunk as a file named `<storyId>_part_<NNN>.mp3`, where `NNN` is zero-padded (`001`, `002`, …, `010`). Padding is optional for correctness but recommended.
2. Add one `audioParts` entry per chunk. In Make's HTTP module, add the field repeatedly with the file data from each TTS output. Use an **Iterator** over the chunks sorted by number, and a **Array aggregator** feeding the HTTP module, or add the fields explicitly if you have a fixed chunk count.
3. Make sure the file name passed with each attachment is the chunk's name, not a generated one. Make's `filename` for an uploaded file is what the service reads.

Common mistakes:

- Chunk names that do not start with exactly the `storyId` → `400 INVALID_AUDIO_PARTS`.
- Gaps in numbering (for example `_part_001` and `_part_003` with no `_part_002`) → `400 Missing part number 2.`
- Two chunks with the same number → `400 Duplicate part number N.`

### Response

Success (`200`):

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
  "audioDownloadUrl": "https://<domain>/api/v1/media/STORY-001/audio",
  "videoDownloadUrl": "https://<domain>/api/v1/media/STORY-001/video",
  "requestId": "c21477ef-f501-423d-9ebc-2b526bc07c8c"
}
```

Map these fields in the next modules:

- `status` must equal `COMPLETED` before anything is downloaded or written to the sheet. Use a Router filter on it.
- `audioDownloadUrl` and `videoDownloadUrl` are the download endpoints. They require the same `X-API-Key` header.

Errors return `4xx`/`5xx` with `code`, `message` and `requestId`. The HTTP module can be set to not throw on error so that the router can branch; set **Evaluate all states as errors** to *No* and route on the HTTP status.

## Module 2: download the merged MP3

Module: **HTTP → Make a request**

| Setting | Value |
|---|---|
| URL | `{{1.audioDownloadUrl}}` |
| Method | `GET` |
| Headers | `X-API-Key: {{your key}}` |
| Parse response | No |
| Response type | Binary (Make stores it as a file) |

Verify the response status is `200` and the file size equals `mergedAudioSizeBytes` before continuing. A mismatch means a truncated download; retry once.

## Module 3: download the MP4

Same as Module 2 with `{{1.videoDownloadUrl}}`.

## Module 4 and 5: upload to Google Drive

Module: **Google Drive → Upload a file**

- Folder: the story's folder in Drive. Use the folder ID stored in the Sheet for this row, or a Drive "Search folders" module keyed on `storyId`.
- File name: `{{1.mergedAudioFilename}}` and `{{1.videoFilename}}`.
- Data: the binary output from Modules 2 and 3.

Make's Drive module returns the file ID and a shareable link. Keep the Drive file ID for the sheet if you want a permanent reference.

## Module 6: update the Google Sheet

Module: **Google Sheets → Update a row**

Write only after both uploads succeed.

| Column | Value |
|---|---|
| S | the verified merged-audio URL (Drive link from module 4, or the download URL if you prefer the service URL) |
| T | the verified video URL (Drive link from module 5) |

Use Drive links for anything a human or YouTube upload step will read later. The service's download URLs expire with the retention period and need the API key.

## Failure handling

Put a **Router** after Module 1 and after each later step.

- **Module 1 not `200`.** Do not touch columns S and T. Write the error code and `requestId` into a status or error column, then stop. The next scheduled run can retry (see [Retries](#retries-and-idempotency)).
- **Download fails.** Retry the download twice with a short delay. If it still fails, do not update S or T.
- **Drive upload fails.** Do not update S or T. If the audio uploaded and the video did not, you can keep the audio link; but leave T empty so the row is not marked complete.
- **Sheet update fails.** Log the error. The media is already in Drive; re-running only the sheet step is safe.

Never write a URL into S or T before every file it points to exists.

## Retries and idempotency

- Re-running `POST /api/v1/media/process` for the same `storyId` is safe. The service regenerates both outputs and replaces the previous ones atomically. A failed rerun never replaces a valid output with a partial one.
- The response always describes the files it just produced. The service never returns stale output as new.
- **Do not repeat OpenAI TTS to retry rendering.** TTS is the expensive step and the chunks already exist. If Module 1 fails, first check whether the chunk files are still in the scenario's data store or Drive, and re-send them to Module 1 rather than calling TTS again. Store the chunk file IDs in the Sheet or a Data Store so a retry can reuse them.
- Concurrent requests for the same `storyId` get `409 STORY_BUSY`. Make a single run per row; use Make's **Ignore repeated executions** or a Sheet status column to avoid overlapping runs.
- A `503 SERVICE_BUSY` means the service is at capacity. Retry after 30–60 s with exponential back-off, up to 3 times.
- Use the `X-Request-Id` header to tie a Make run to Railway logs.

Suggested retry policy for Module 1:

| Response | Action |
|---|---|
| `200` | Continue |
| `503` (any code) | Wait 30 s, retry up to 3 times |
| `504 PROCESSING_TIMEOUT` | Do not blindly retry; the story may be too long. Log and alert |
| `500 PROCESSING_FAILED` / `OUTPUT_VERIFICATION_FAILED` | Do not retry automatically; inspect the inputs (chunk names, audio) using `requestId` |
| `4xx` | Do not retry; fix the request |

## Long stories and timeouts

The processing request is synchronous. The HTTP module must wait for the whole render. Expect the response to take roughly as long as the narration is long (measured 2.5 s for 12 parts totalling 6 s on a workstation; re-measure on Railway).

- Set the Make HTTP timeout to at least 300 s. Increase it if you process long stories, and benchmark first.
- Make.com scenarios have a maximum run time. If a render can approach that limit, the current API cannot split one story across several calls (each call replaces the story's outputs). Options are to shorten the narration per story, or to adopt the asynchronous design below.
- `PROCESSING_TIMEOUT_SECONDS` bounds each FFmpeg step on the server (default 900 s). A `504` means the server gave up.

### Future asynchronous design (not implemented)

If synchronous rendering is not reliable enough, the next step is a job API:

1. `POST /api/v1/media/jobs` accepts the same multipart body and returns `202` with a `jobId`.
2. `GET /api/v1/media/jobs/{jobId}` returns `QUEUED`, `RUNNING`, `COMPLETED` (with the same fields as the synchronous response) or `FAILED` (with `code`).
3. Make polls with a Sleep module, or the service calls a Make webhook when done.

This needs a job store (in memory is lost on restart; a database or Redis would be needed for durability). It was deliberately left out of version 1 because the synchronous path meets the current need and adds no infrastructure.

## Expired or missing outputs

If a download returns `404 OUTPUT_NOT_FOUND`, the output was deleted (retention) or the instance was redeployed. Re-run Module 1 with the same chunks. Do not re-run TTS.

## Testing the scenario safely

1. Use a test story ID (for example `TEST-MAKE-001`) and two short chunks.
2. Run Module 1 alone first and inspect the response.
3. Run the downloads and check file sizes against the response.
4. Only then connect the Drive and Sheet modules, and test with a row you can delete.
5. Call `DELETE /api/v1/media/TEST-MAKE-001` with the key afterwards to clean up.

## Checklist before going live

- [ ] API key stored in a Make connection, not in plain module fields
- [ ] Chunk names match `<storyId>_part_<NNN>.mp3` exactly
- [ ] Router on `status == COMPLETED` before any download
- [ ] Downloads verified against `*SizeBytes`
- [ ] S and T written only after both Drive uploads succeed
- [ ] Retry policy in place for `503`; no automatic retry for `500`/`504`
- [ ] HTTP timeout at least 300 s
- [ ] A scenario run tested end-to-end (not yet done as part of this work)
