# Security

## Threat model (short)

The service is a private API. Its callers are an automation platform (Make.com) that holds one shared key. The main risks are:

- unauthorised use of the render capacity or the stored outputs,
- malicious uploads (executables disguised as media, malformed containers),
- path traversal or command injection through identifiers and filenames,
- resource exhaustion through large or numerous uploads, or long-running FFmpeg work,
- leaking internal details or secrets in logs and error responses.

## Authentication

- One API key, configured as `API_KEY`. Every `/api/**` endpoint requires it, including downloads and deletion.
- Accepted as `X-API-Key: <key>` or `Authorization: Bearer <key>`.
- Comparison: both values are hashed with SHA-256 and compared with `MessageDigest.isEqual`, which runs in constant time regardless of where the first difference is.
- Missing or wrong keys return `401` with a JSON body and `WWW-Authenticate: ApiKey`.
- Health (`/actuator/health`) and API docs are public. Health shows only `UP`/`DOWN`. Set `OPENAPI_ENABLED=false` to hide the API docs in production.
- There are no user accounts, sessions, JWT or OAuth. A single key is sufficient for one private integration.

## Secrets

- `API_KEY` comes only from the environment. The image contains no secret; the Dockerfile sets no key.
- The `prod` profile refuses to start without a key of at least 32 characters. The error message does not echo the value.
- A missing key in any profile rejects all `/api/**` requests (fail closed).
- `.env` is git-ignored. `.env.example` contains placeholders only.
- The API key is never logged. Authorization headers are not logged. Request logs record method and path only.
- Rotate by changing `API_KEY` on the platform and updating callers. There is no key-grace period; old keys stop working on redeploy.

Generate a key:

```bash
openssl rand -hex 32
```

## Input validation

| Input | Control |
|---|---|
| `storyId` | Regex `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`. Dots and slashes are rejected, so `..` cannot occur |
| Part filenames | Basename only (both `/` and `\` stripped), unsafe characters replaced, then matched against `<storyId>_part_<n>.mp3` |
| Content type | Detected from magic bytes; the declared type and extension are ignored |
| Executables and archives | Rejected by signature before any tool sees them: Windows PE (`MZ`), ELF, Mach-O, shebang scripts, ZIP/JAR/Office containers |
| Audio and images | FFprobe must read them and find the expected codec and stream type |
| Render options | Bean Validation ranges; `audioBitrate` and `backgroundColor` match strict patterns; dimensions must be even |
| Request ID | Accepted only if 8-64 characters of `[A-Za-z0-9-]`; otherwise replaced. This prevents header injection into logs |

## Filesystem safety

- Every output and workspace path is built from a validated `storyId` and then normalised. The result must start with the configured root and must not equal the root.
- Symbolic links are refused for story directories and outputs. Deletion walks trees without following links.
- Uploads are written to generated names (`part_001.mp3`, `image.png`), never to client-chosen names.
- Each job has a unique workspace (`job-<uuid>`), deleted in a `finally` block.
- The service refuses to start if the output directory sits inside the temp directory, because startup cleanup would otherwise delete outputs.
- The service reads and writes only under `MEDIA_TEMP_DIR` and `MEDIA_OUTPUT_DIR`.

## Command execution

- FFmpeg and FFprobe are launched with `ProcessBuilder` from an argument list. No shell is involved.
- Argument values are either fixed strings, numbers, values checked against patterns, or paths the service generated.
- Each process has a timeout and is force-killed when exceeded.
- Output is redirected to files inside the job workspace and read back with a size cap, so a chatty process cannot exhaust memory.
- FFmpeg's stderr is logged server-side at `WARN`, with the request ID. Clients receive a generic message.

## Resource limits

| Limit | Default | Enforced by |
|---|---|---|
| Whole request | 500 MB | Spring multipart and Tomcat |
| Single file | 25 MB | Spring multipart and the processing service |
| Multipart parts per request | 250 | Tomcat `max-part-count` |
| Audio parts per story | 200 | `AudioPartParser` |
| Narration duration | 7200 s | Processing service, after probing |
| Concurrent FFmpeg jobs | 2 | `JobGate` (503 when full) |
| Same story concurrently | 1 | `JobGate` (409) |
| Time per FFmpeg step | 900 s | `CommandRunner` |
| Free disk | 512 MB + 4x input | Admission check (507) |
| Output retention | 24 h | Hourly sweep |

## Error disclosure

- Error bodies contain a stable `code`, a fixed-per-code message and the `requestId`.
- Messages never include file paths, commands, stack traces, FFmpeg output or configuration values.
- Stack traces and FFmpeg stderr are logged server-side only, under the request ID.

## Logging

- Logged: request IDs, story IDs, part counts, sizes, durations and error codes.
- Not logged: API keys, authorization headers, media content, filenames supplied by callers beyond sanitised names in parser errors.

## Container hardening

- Runs as an unprivileged system user (`media`), no login shell.
- Writable paths limited to `/data` (temp and output).
- No secrets baked into the image. No ports other than 8080.
- Base image from Eclipse Temurin on Ubuntu 24.04; FFmpeg from the distribution's apt repository.
- Recommended for production: keep the base image updated and rebuild regularly to receive security fixes.

## Known gaps

- A single shared key. Rotating it requires coordinated updates.
- No rate limiting. Use the platform or a proxy if callers can be untrusted.
- No audit trail beyond logs.
- Download URLs use the same key, so anyone holding the key can fetch any story's output. Scope keys per environment.
- Dependencies are pinned in `backend/build.gradle`; run a dependency vulnerability scan (for example `./gradlew dependencyCheckAnalyze` once the OWASP plugin is added) before production use. This was not run as part of this work.

## Reporting

Report suspected vulnerabilities to the repository owner directly. Do not post keys or sample payloads containing real story content in public issues.
