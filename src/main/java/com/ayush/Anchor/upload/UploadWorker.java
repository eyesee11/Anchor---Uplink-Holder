package com.ayush.Anchor.upload;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ayush.Anchor.manifest.FileRecord;
import com.ayush.Anchor.manifest.ManifestStore;

/*
 * UploadWorker — the engine that connects the watcher to Google Drive.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY does this class exist separately?
 * ─────────────────────────────────────────────────────────────────────────────
 * The FolderWatcher detects files and calls onNewFile(path).
 * But uploading is slow (network I/O). If we uploaded inside the watcher's
 * callback we would:
 *   1. Block the watcher thread during the upload — missing new file events
 *   2. Lose progress if the process is killed mid-upload
 *
 * Solution: the watcher just enqueues file paths into a BlockingQueue.
 * UploadWorker runs on its OWN thread, drains that queue, and uploads one
 * file at a time. They are fully decoupled.
 *
 * On startup, UploadWorker also re-queues any PENDING or UPLOADING files
 * left over from a previous crashed run — this is the "resume on restart"
 * feature.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Thread model
 * ─────────────────────────────────────────────────────────────────────────────
 *   Thread 1 (main / AddFolderCommand): starts watcher + worker
 *   Thread 2 (folder-watcher):          detects files → queue.put(path)
 *   Thread 3 (upload-worker):           queue.take(path) → upload to Drive
 */
public class UploadWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(UploadWorker.class);

    /*
     * BlockingQueue<FileRecord>
     *
     * A thread-safe queue. The watcher thread puts() items in; we take() them out.
     * When the queue is empty, take() blocks (puts this thread to sleep) until
     * something arrives — zero CPU waste while idle.
     *
     * LinkedBlockingQueue = unbounded FIFO; fine for our use case since the
     * watcher won't produce faster than we can consume over time.
     */
    private final BlockingQueue<FileRecord> queue = new LinkedBlockingQueue<>();

    private final ManifestStore     manifest;
    private final ResumableUploader uploader;
    private volatile boolean        running = true;  // set to false for graceful shutdown

    /*
     * Constructor — receives already-constructed dependencies.
     *
     * WHY inject rather than construct inside?
     * Both ManifestStore (DB connection) and ResumableUploader (Drive connection)
     * are expensive to create and should be shared across the application lifecycle.
     * Injecting them here makes UploadWorker easy to test and avoids duplicating
     * the setup logic.
     */
    public UploadWorker(ManifestStore manifest, ResumableUploader uploader) {
        this.manifest = manifest;
        this.uploader = uploader;
    }

    /*
     * enqueue(record) — called by the FolderWatcher callback (on watcher thread).
     *
     * put() is non-blocking for the caller (the watcher) as long as the queue
     * is unbounded. The worker thread will pick it up asynchronously.
     */
    public void enqueue(FileRecord record) {
        queue.add(record);
        log.debug("Queued for upload: {}", record.absolutePath);
    }

    /*
     * recoverPending() — call once on startup BEFORE starting the run() loop.
     *
     * Reads all PENDING and UPLOADING records from the manifest and re-adds
     * them to the queue. This is how we survive a crash or restart:
     *   - PENDING  = never started, start fresh
     *   - UPLOADING = started but not finished, will resume from saved offset
     *
     * WHY UPLOADING is recoverable:
     *   ManifestStore.updateProgress() saved the resumeUri + bytesUploaded
     *   after every chunk. ResumableUploader.startOrResumeSession() checks
     *   for a non-null resumeUri and reuses it instead of creating a new session.
     */
    public void recoverPending() {
        try {
            List<FileRecord> pending = manifest.getAll();
            int count = 0;
            for (FileRecord r : pending) {
                if (r.status == FileRecord.Status.PENDING
                        || r.status == FileRecord.Status.UPLOADING) {
                    queue.add(r);
                    count++;
                }
            }
            if (count > 0) {
                log.info("Recovered {} file(s) from previous run", count);
            }
        } catch (Exception e) {
            log.error("Failed to recover pending files", e);
        }
    }

    /*
     * run() — the worker loop. Runs on its own thread ("upload-worker").
     *
     * Lifecycle:
     *   1. poll() waits up to 2 seconds for a new item
     *   2. If nothing arrives (null), check `running` — shutdown if false
     *   3. If an item arrives, upload it
     *   4. On success: manifest marks DONE; on failure: manifest marks ERROR
     *
     * WHY poll(2, SECONDS) and not take()?
     *   take() blocks indefinitely — if stop() sets running=false while we're
     *   blocked in take(), we'd never wake up. poll with a timeout lets us
     *   check the `running` flag every 2 seconds.
     */
    @Override
    public void run() {
        log.info("Upload worker started");

        while (running) {
            FileRecord record = null;
            try {
                // Block up to 2 seconds for a new file to appear in the queue
                record = queue.poll(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (record == null) {
                // Timeout — no new file, loop back and check `running`
                continue;
            }

            log.info("Starting upload: {} ({} bytes)", record.absolutePath, record.sizeBytes);

            try {
                uploader.uploadFile(record);
                // uploadFile() calls manifest.markDone() internally on success
                log.info("Upload finished: {}", record.absolutePath);

            } catch (Exception e) {
                // Network error, Drive quota, etc.
                // Mark ERROR so `anchor status` shows it and the user can retry.
                log.error("Upload failed for {}: {}", record.absolutePath, e.getMessage());
                try {
                    manifest.markError(record.absolutePath, e.getMessage());
                } catch (Exception dbEx) {
                    log.error("Could not mark ERROR in manifest", dbEx);
                }
            }
        }

        log.info("Upload worker stopped");
    }

    /*
     * stop() — signals the run() loop to exit cleanly.
     *
     * `volatile` on `running` ensures the write here is immediately visible
     * to the run() loop on the other thread without needing synchronisation.
     * The loop exits within 2 seconds (the poll timeout).
     */
    public void stop() {
        running = false;
    }
}
