<p align="center">
  <img src="logo.png" alt="Anchor Logo" width="200"/>
</p>

<h1 align="center">Anchor -- Windows Upload Stabilizer</h1>

A Windows CLI tool that watches a local folder and uploads every file inside it to Google Drive using **resumable uploads**. If your connection drops mid-upload, Anchor picks up from exactly where it left off (or maybe not, just 8mb usage wasted 😛).

---

## The Problem Anchor Solves

Uploading large files (videos, archives, backups) over an unstable connection is painful. Standard upload clients:

- Restart from byte 0 on any failure
- Give you no visibility into what was uploaded vs. what is pending
- Block the terminal while uploading

Anchor fixes all three: it saves a **resume URI** and **byte offset** to a local SQLite database after every chunk. On restart, it reads those two values and tells Google Drive "continue from here."

---

## FAQ: Why Anchor?

**Q: Doesn’t Google Drive already support resumable uploads?**

**A:** Google Drive’s web interface and desktop sync client do not expose chunked resumable uploads to users. If you upload a large file via the browser or Drive for Desktop, a network failure often restarts the upload from zero. Anchor uses Google Drive’s API-level resumable upload protocol, saving progress after every 8MB chunk. If your connection drops, Anchor resumes from the last saved chunk, never wasting bandwidth.

**Q: What does Anchor do differently?**

- Monitors a folder on your device
- Tracks files in a local SQLite database
- Hashes files natively for speed
- Uploads files in 8MB chunks
- Recovers from network failures automatically
- Runs as a CLI tool, not a background sync client

This makes Anchor ideal for unreliable connections, large files, and power users who want granular control and visibility.

## Usage

```
anchor add-folder <path>    Watch a folder and sync all files inside to Drive
anchor status               Show upload state of every tracked file
anchor --help               Show help
anchor --version            Show version
```

Example output of `anchor status`:

```
FILE                                                    SIZE   PROGRESS     STATUS
──────────────────────────────────────────────────────────────────────────────────
holiday_2025.mp4                                      2.1 GB      70%   UPLOADING
report_final.pdf                                      1.8 MB     100%       DONE
backup.zip                                            800 MB       0%    PENDING
```

---

## Project Structure

```
Anchor/
├── pom.xml                          Maven build + dependency config
├── c-hasher/
│   └── hasher.c                     Native C binary — fast SHA-256 hashing
└── src/main/java/com/ayush/Anchor/
    ├── Main.java                    Entry point, root CLI command
    ├── cli/
    │   ├── AddFolderCommand.java    `anchor add-folder` subcommand
    │   └── StatusCommand.java       `anchor status` subcommand
    ├── hasher/
    │   ├── CHasher.java             Runs the C binary as a subprocess
    │   └── HashResult.java          Maps the C binary's JSON output to Java
    ├── manifest/
    │   ├── FileRecord.java          Data model — one row in the SQLite DB
    │   └── ManifestStore.java       All SQL — the only place that knows the DB exists
    ├── upload/
    │   ├── DriveAuthHelper.java     OAuth 2.0 flow — gets a Drive API credential
    │   └── ResumableUploader.java   Chunked Google Drive upload with resume logic
    └── watcher/
        └── FolderWatcher.java       Watches a folder using OS-native file events
```

---

## File-by-File Explanation

### `pom.xml` — The Build File

Maven's configuration file. Declares:

| Declaration                            | Why                                                                                                |
| -------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `groupId / artifactId / version`       | Maven's "address" for this project in the dependency ecosystem                                     |
| `maven.compiler.source/target = 17`    | Forces Java 17 syntax and bytecode                                                                 |
| `picocli 4.7.5`                        | Turns `main()` into a full CLI with subcommands, flags, `--help`, and coloured output              |
| `sqlite-jdbc 3.45`                     | JDBC driver for SQLite — file-based database, no server needed                                     |
| `google-api-services-drive`            | Official Google Drive API Java client                                                              |
| `google-auth-library-oauth2-http`      | Manages the OAuth 2.0 token lifecycle automatically                                                |
| `google-oauth-client-java6`            | Provides `AuthorizationCodeInstalledApp` — the installed-app OAuth flow                            |
| `google-oauth-client-jetty`            | Provides `LocalServerReceiver` — local HTTP server on `localhost:9876` to catch the OAuth redirect |
| `jackson-databind`                     | Reads the JSON that the C hasher prints to stdout                                                  |
| `slf4j-api` + `logback-classic`        | Logging interface + implementation. SLF4J is the API, Logback is the engine                        |
| `maven-shade-plugin`                   | Bundles all dependencies into one fat JAR so you can run `java -jar Anchor.jar` anywhere           |
| `picocli-codegen` annotation processor | Generates picocli CLI metadata at compile time — faster startup, better GraalVM support            |

---

### `Main.java` — Entry Point

```
anchor add-folder   →   AddFolderCommand.run()
anchor status       →   StatusCommand.run()
anchor help         →   prints usage
anchor (nothing)    →   prints usage (via @Spec + spec.commandLine().usage())
```

**Why `implements Runnable`?**  
Picocli requires the root command to be `Runnable` or `Callable`. When the user types just `anchor` with no subcommand, `run()` is invoked — we use it to print help instead of silently doing nothing.

**Why `System.exit(exitCode)`?**  
Scripts (CI pipelines, batch files) check the exit code of commands they run. `0 = success`, non-zero = failure. Without `System.exit()`, a failed subcommand would still exit 0.

---

### `cli/AddFolderCommand.java` — `anchor add-folder <path>`

Orchestrates the entire pipeline for a given folder:

1. Validates the path is a real directory
2. Opens the `ManifestStore` database connection
3. Scans existing files → hashes each with `CHasher` → inserts them as `PENDING`
4. Starts `FolderWatcher` on a daemon thread to catch future file additions
5. Blocks (`watchThread.join()`) keeping the process alive until `Ctrl+C`

**Why a daemon thread?**  
`setDaemon(true)` tells the JVM: "don't keep the process alive just for this thread." If the user presses `Ctrl+C`, the JVM can shut down even if the watcher loop is blocked waiting for events.

**`findHasherBinary()`**  
Locates `hasher.exe` (the compiled C binary) in this priority order:

1. `ANCHOR_HASHER` environment variable (most flexible — good for CI/CD)
2. Same directory as the running JAR
3. `~/.anchor/hasher.exe` (convention for user-level tools)

---

### `cli/StatusCommand.java` — `anchor status`

Queries all rows from the `ManifestStore` and prints a formatted table. Uses `printf` with `%-50s` etc. column widths for aligned output.

`formatBytes()` converts raw byte counts to human-readable strings (`1.5 GB`, `800 MB`).

---

### `hasher/HashResult.java` — C Binary Output Model

A plain data class. Its only job is to be the target of Jackson's JSON deserializer:

```json
{
  "file": "C:\\Videos\\movie.mp4",
  "sha256": "a3f1c2...",
  "size_bytes": 1572864
}
```

Jackson reads that string → produces a `HashResult` with those 3 fields populated.  
`@JsonProperty("size_bytes")` maps the snake_case JSON key to the camelCase Java field `sizeBytes`.

---

### `hasher/CHasher.java` — Subprocess Launcher

**Why a native C binary for hashing instead of Java's `MessageDigest`?**  
Java's SHA-256 is fine for small files, but for a 10 GB video it's measurably slower due to JVM overhead. The C binary using OpenSSL's optimised SHA-256 (which uses hardware AES-NI instructions) is 3–5× faster.

**How it works:**

1. `ProcessBuilder(hasherBinaryPath, filePath)` — spawns `hasher.exe C:\Videos\movie.mp4`
2. Reads all of the process's `stdout` — that's the JSON line
3. Calls `waitFor()` for the exit code (after reading stdout — reading first avoids a deadlock if the output buffer fills up)
4. Jackson deserialises the JSON into `HashResult`

---

### `manifest/FileRecord.java` — Database Row Model

Represents one tracked file. Fields split into two groups:

| Field           | `final`? | Why                                        |
| --------------- | -------- | ------------------------------------------ |
| `absolutePath`  | yes      | Identity — never changes                   |
| `sizeBytes`     | yes      | Fixed at the moment of hashing             |
| `sha256`        | yes      | Fixed — the file's fingerprint             |
| `status`        | no       | Changes: PENDING → UPLOADING → DONE/ERROR  |
| `bytesUploaded` | no       | Updated after every chunk                  |
| `resumeUri`     | no       | Set when session starts, cleared when done |

**Why an enum for status instead of a String?**  
A typo like `"PENIDNG"` compiles fine as a string. `Status.PENIDNG` is a compile error. Enums also enable `switch` statements.

---

### `manifest/ManifestStore.java` — Database Access Layer

The only class in the project that writes SQL. Every other class stays completely unaware that SQLite exists.

**`implements AutoCloseable`**  
Enables `try-with-resources` usage:

```java
try (ManifestStore manifest = new ManifestStore()) { ... }
```

Java automatically calls `manifest.close()` — closing the database connection — when the block exits, even on exception.

**`CREATE TABLE IF NOT EXISTS`**  
Idempotent. Run on every startup: first run creates the table, subsequent runs do nothing. No need to check "does the DB exist yet."

**`PreparedStatement` everywhere, never string concatenation**  
`ps.setString(1, value)` ensures `value` is treated as data, never as SQL. String concatenation would allow SQL injection — e.g. a filename like `'); DROP TABLE files;--` would delete the database.

**`INSERT OR IGNORE`**  
If the watcher fires twice for the same file (which can happen on Windows), the second insert is silently skipped. The `UNIQUE` constraint on `absolute_path` enforces this at the database level.

**`updateProgress()` — the core of Anchor**  
Called after every 8 MB chunk is confirmed uploaded. Writes `bytesUploaded` and `resumeUri` to disk. If the process is killed after this write, the next run reads these values and resumes from the saved offset.

---

### `upload/DriveAuthHelper.java` — OAuth 2.0

Handles the one-time browser login and token persistence.

**First run:**

1. Opens `accounts.google.com/o/oauth2/auth` in the default browser
2. User grants permission
3. Google redirects to `localhost:9876` (our embedded Jetty server)
4. The Jetty server captures the auth code
5. Auth code is exchanged for an access token + refresh token
6. Tokens saved to `~/.anchor/tokens/`

**Subsequent runs:**  
Reads the saved refresh token from disk → silently gets a new access token. No browser interaction needed.

**`DriveScopes.DRIVE_FILE`**  
Principle of least privilege — Anchor only requests access to files it creates. It cannot read or modify any pre-existing Google Drive files.

**`credentials.json`**  
Downloaded from Google Cloud Console. Contains your OAuth client ID and secret. Must be placed at `~/.anchor/credentials.json` before first run.

---

### `upload/ResumableUploader.java` — Chunked Upload Engine

**Why resumable uploads?**  
Google Drive's standard upload requires the entire file in one HTTP request. A single packet loss at byte 8,999,999,999 of a 9 GB file restarts everything. Resumable uploads split the file into chunks; each chunk is independently retryable.

**`startOrResumeSession()`**

- If `record.resumeUri != null` (already started) → returns the saved URI directly. No new session created.
- If null (first attempt) → POSTs to `https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable`. Google responds with a `Location` header containing a unique session URI. This URI is saved to `ManifestStore` _before uploading a single byte_.

**`uploadFile()` loop:**

1. `RandomAccessFile.seek(offset)` — jumps to the last confirmed byte position
2. Reads 8 MB into `buffer`
3. Builds a `PUT` request with `Content-Range: bytes START-END/TOTAL`
4. Executes the request
5. On success: increments `offset`, saves new offset to DB
6. Repeat until `offset == totalSize`

**`CHUNK_SIZE = 8 MB`**  
Google requires chunk sizes to be exact multiples of **256 KB** (except the last chunk). 8 MB (= 32 × 256 KB) is efficient without being so large that a failure wastes significant progress.

---

### `watcher/FolderWatcher.java` — OS File-Event Listener

**Why not polling?**  
A `while(true) { Thread.sleep(1000); scanFolder(); }` loop burns CPU and misses rapid changes. Instead, `WatchService` hooks into:

- Windows: `ReadDirectoryChangesW`
- Linux: `inotify`
- macOS: `kqueue`

The OS pushes events to the application — zero CPU between changes.

**`volatile boolean running`**  
Standard graceful-shutdown pattern for threads. When another thread sets `watcher.stop()`, the watcher's loop reads the updated `running = false` on its next iteration. `volatile` ensures CPU-cache coherency — without it, the write in one thread might never be seen by another.

**`key.reset()`**  
Mandatory after calling `key.pollEvents()`. Failing to call this is the #1 `WatchService` bug — the key enters a consumed state and all future file events are silently dropped.

**`ws.poll(1, SECONDS)` vs `ws.take()`**  
`take()` blocks forever — can't be interrupted by `running = false`. `poll(1, SECONDS)` gives the loop a chance to check `running` every second, enabling clean shutdown on `Ctrl+C`.

**`Consumer<Path> onNewFile`**  
A functional interface (lambda) passed in by the caller. The watcher doesn't import or depend on the uploader — it just calls `onNewFile.accept(path)`. This decouples the two components.

---

## Data Flow

```
anchor add-folder C:\Videos
         │
         ▼
AddFolderCommand.run()
  ├─ Scan existing files in C:\Videos
  │     └─ CHasher.hash(file) ──────────────────────────────► hasher.exe
  │            └─ HashResult {sha256, sizeBytes}  ◄───────────
  │     └─ ManifestStore.insertFile(PENDING)
  │
  └─ FolderWatcher.start() [daemon thread]
        └─ OS event: new file detected
              └─ CHasher.hash() + ManifestStore.insertFile(PENDING)
```

```
[Upload worker — Phase 2]
  ManifestStore.getAll(PENDING)
         │
         ▼
  DriveAuthHelper.buildDriveService()   (OAuth, once)
         │
         ▼
  ResumableUploader.startOrResumeSession()
    ├─ existing resumeUri?  YES ──► use saved URI
    └─ NO ──► POST to Drive API, save new URI to ManifestStore
         │
         ▼
  ResumableUploader.uploadFile()
    ├─ seek() to saved bytesUploaded offset
    ├─ upload 8MB chunk → Content-Range header
    ├─ ManifestStore.updateProgress(newOffset, resumeUri)
    └─ repeat until done
         │
         ▼
  ManifestStore.markDone()
```

---

## What Is Complete vs. What Is Pending

---

## Project Completion

All core features of Anchor are fully implemented:

- Folder watching and registration
- Fast native SHA-256 hashing
- SQLite manifest tracking
- Resumable chunked uploads to Google Drive
- OAuth 2.0 authentication
- Background upload worker
- Logging via logback.xml
- Status reporting via CLI

## The tool is ready for real-world use.



## How to Use Anchor via Website Beta

1. Go to the Anchor website and download the `.jar` file and the provided `credentials.json`.
2. Submit your Gmail address via the website form (or email) to request access as a test user.
3. Once you are added as a test user, place the downloaded `credentials.json` in your `~/.anchor` folder.
4. Run Anchor from the command line:
  ```
  java -jar Anchor.jar add-folder "C:\Your\Folder\Path"
  ```
5. On first run, a browser window will open for Google authentication. Log in with your Gmail (the one you submitted).
6. Anchor will monitor your folder and upload files to Google Drive in the background.

**Note:** You do NOT need to create your own Google Cloud project or credentials. Use the file provided by the website.



## Dependencies Summary

| Library                         | Version          | Role                          |
| ------------------------------- | ---------------- | ----------------------------- |
| picocli                         | 4.7.5            | CLI framework                 |
| sqlite-jdbc                     | 3.45.3.0         | SQLite database driver        |
| google-api-services-drive       | v3-rev197-1.25.0 | Google Drive REST API client  |
| google-auth-library-oauth2-http | 1.23.0           | OAuth 2.0 token management    |
| google-oauth-client-java6       | 1.34.1           | Installed-app OAuth flow      |
| google-oauth-client-jetty       | 1.34.1           | Local OAuth redirect receiver |
| jackson-databind                | 2.17.1           | JSON parsing                  |
| slf4j-api                       | 2.0.13           | Logging API                   |
| logback-classic                 | 1.5.6            | Logging implementation        |
