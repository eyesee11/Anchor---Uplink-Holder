package com.ayush.Anchor.cli;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ayush.Anchor.hasher.CHasher;
import com.ayush.Anchor.hasher.HashResult;
import com.ayush.Anchor.manifest.FileRecord;
import com.ayush.Anchor.manifest.ManifestStore;
import com.ayush.Anchor.watcher.FolderWatcher;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

// ─────────────────────────────────────────────────────────────────────────────
// @Command — subcommand definition
// ─────────────────────────────────────────────────────────────────────────────
// name        : the keyword used in the CLI → `anchor add-folder <path>`
// description : shown in --help output for this specific subcommand
// ─────────────────────────────────────────────────────────────────────────────
@Command(
    name = "add-folder",
    description = "Register a local folder to be watched and synced to Google Drive"
)
public class AddFolderCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AddFolderCommand.class);

    // ─────────────────────────────────────────────────────────────────────────
    // @Parameters
    // ─────────────────────────────────────────────────────────────────────────
    // A @Parameters is a POSITIONAL argument (no flag prefix like --folder).
    // When user types: `anchor add-folder C:\Videos`
    // picocli sees "C:\Videos" is in position 0 → injects it into `folderPath`.
    //
    // paramLabel : shown in help as placeholder, e.g. "anchor add-folder <path>"
    // description: shown in --help for this argument
    // ─────────────────────────────────────────────────────────────────────────
    @Parameters(
        index = "0",
        paramLabel = "<path>",
        description = "Absolute path to the local folder to watch"
    )
    private String folderPath;

    @Override
    public void run() {
        Path path = Paths.get(folderPath);

        if (!Files.isDirectory(path)) {
            System.err.println("ERROR: Not a valid directory: " + folderPath);
            return;
        }

        try (ManifestStore manifest = new ManifestStore()) {
            // Hash + register all existing files in the folder
            CHasher hasher = new CHasher(findHasherBinary());
            Files.list(path)
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    try {
                        HashResult hr = hasher.hash(file);
                        manifest.insertFile(new FileRecord(
                            hr.file, hr.sizeBytes, hr.sha256, FileRecord.Status.PENDING));
                        log.info("Registered: {}", file);
                    } catch (IOException | InterruptedException | SQLException e) {
                        log.error("Failed to register: {}", file, e);
                    }
                });

            // Start watching for new files
            FolderWatcher watcher = new FolderWatcher(path, newFile -> {
                try {
                    HashResult hr = hasher.hash(newFile);
                    manifest.insertFile(new FileRecord(
                        hr.file, hr.sizeBytes, hr.sha256, FileRecord.Status.PENDING));
                } catch (IOException | InterruptedException | SQLException e) {
                    log.error("Watcher error: {}", e);
                }
            });

            Thread watchThread = new Thread(watcher, "folder-watcher");
            watchThread.setDaemon(true);

/*
setDaemon(true) means the JVM won't wait for this thread to finish if main() returns — 
important so Ctrl+C actually kills the process. 
watchThread.join() blocks the current thread, keeping the process alive while the watcher runs.
*/
            watchThread.start();

            System.out.println("Watching: " + folderPath);
            System.out.println("Press Ctrl+C to stop.");
            watchThread.join();   // block until interrupted

        } catch (Exception e) {
            log.error("add-folder failed", e);
        }
    }

    // Locate the compiled C hasher binary.
    // Priority: ANCHOR_HASHER env var → same dir as JAR → ~/.anchor/hasher.exe
    private String findHasherBinary() {
        String env = System.getenv("ANCHOR_HASHER");
        if (env != null && !env.isBlank()) return env;

        try {
            Path jarDir = Paths.get(
                AddFolderCommand.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()
            ).getParent();
            Path candidate = jarDir.resolve("hasher.exe");
            if (Files.isExecutable(candidate)) return candidate.toString();
        } catch (URISyntaxException | SecurityException ignored) {}

        return System.getProperty("user.home") + "/.anchor/hasher.exe";
    }
}
