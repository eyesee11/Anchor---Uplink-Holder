package com.ayush.Anchor.watcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.function.Consumer;

/* 
java.nio.file.WatchService is the Java API that hooks into the OS's native file system change notification 
(on Windows: ReadDirectoryChangesW, on Linux: inotify). 
This is better than polling because polling (while(true) { sleep(1000); scan folder; }) 
burns CPU and misses rapid changes. The OS pushes events to us.
*/


public class FolderWatcher implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(FolderWatcher.class);

    private final Path             watchedFolder;
    private final Consumer<Path>   onNewFile;   // callback
    private volatile boolean       running = true;

/*  
volatile boolean running is the standard graceful shutdown pattern for threads. 
When the main thread sets watcher.running = false, the watcher's loop sees it on the next iteration. 
volatile ensures the write in one thread is immediately visible to reads in another thread 
(without it, the JVM might cache the value in a CPU register and never check the updated value).
*/

        public FolderWatcher(Path watchedFolder, Consumer<Path> onNewFile) {
        this.watchedFolder = watchedFolder;
        this.onNewFile     = onNewFile;
    }
/*
Consumer<Path> is a functional interface: it takes one argument (a Path) and returns nothing. 
The caller passes a lambda like path -> uploadWorker.enqueue(path). 
This decouples the watcher from the uploader — the watcher doesn't import or depend on the uploader.
*/
    @Override
    public void run() {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            watchedFolder.register(ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY);

/*
ENTRY_CREATE fires when a new file appears. ENTRY_MODIFY fires when a file is written to. 
We register BOTH because large files are often written in chunks — 
the OS fires MODIFY events as data arrives, then ENTRY_CREATE when the file handle is closed 
(behavior varies by OS and application).
*/                
                log.info("Watching folder: {}", watchedFolder);

            while (running) {
                WatchKey key = ws.poll(1, java.util.concurrent.TimeUnit.SECONDS);

/*
ws.poll(1, SECONDS) blocks for up to 1 second waiting for events. 
Why poll with timeout instead of ws.take() (which blocks forever)? Because take() can't be interrupted by running = false. 
With poll(1, SECONDS), we check running every second, allowing clean shutdown.
*/
                if (key == null) continue;   // timeout, no events

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;

/*
OVERFLOW means the OS dropped some events because the event queue filled up. 
We skip it here; a more robust implementation would re-scan the folder. 
For now, logging an overflow is acceptable.
*/
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev   = (WatchEvent<Path>) event;
                    Path name = ev.context();          // just the filename
                    Path full = watchedFolder.resolve(name);  // full path

/*
ev.context() returns only the filename (e.g., video.mp4), not the full path. 
watchedFolder.resolve(name) appends it to the folder path to get the full absolute path. 
This is a common WatchService gotcha.
*/
                    if (Files.isRegularFile(full)) {
                        log.debug("File event {}: {}", kind, full);
                        onNewFile.accept(full);   // trigger the callback
                    }
                }
                key.reset();   // MUST call reset() or key is cancelled and events stop
            }
        } catch (IOException | InterruptedException e) {
            log.error("Watcher error: {}", e.getMessage());
        }
    }

    public void stop() { running = false; }
}

/*
key.reset() is mandatory. After key.pollEvents(), the WatchKey enters a consumed state. 
Without reset(), it never transitions back to ready state and you stop receiving events. 
This is the #1 WatchService bug.
*/