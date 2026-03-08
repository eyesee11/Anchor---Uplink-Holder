package com.ayush.Anchor.cli;

import picocli.CommandLine.Command;

import java.nio.file.Paths;
import java.util.List;

import com.ayush.Anchor.manifest.FileRecord;
import com.ayush.Anchor.manifest.ManifestStore;

// ─────────────────────────────────────────────────────────────────────────────
// StatusCommand — `anchor status`
// ─────────────────────────────────────────────────────────────────────────────
// Shows the current state of all tracked files (PENDING, UPLOADING, DONE, ERROR)
// In Phase 2 this will query the SQLite manifest DB and print a formatted table.
// ─────────────────────────────────────────────────────────────────────────────
@Command(
    name = "status",
    description = "Show upload status of all tracked files"
)
public class StatusCommand implements Runnable {

 @Override
public void run() {
    try (ManifestStore manifest = new ManifestStore()) {
        List<FileRecord> files = manifest.getAll();
        if (files.isEmpty()) {
            System.out.println("No files tracked yet.");
            return;
        }

        System.out.printf("%-50s %10s %8s %10s%n", "FILE", "SIZE", "PROGRESS", "STATUS");
        System.out.println("-".repeat(82));

        for (FileRecord r : files) {
            String name     = Paths.get(r.absolutePath).getFileName().toString();
            String size     = formatBytes(r.sizeBytes);
            String progress = r.sizeBytes > 0
                ? (100 * r.bytesUploaded / r.sizeBytes) + "%"
                : "0%";
            System.out.printf("%-50s %10s %8s %10s%n", name, size, progress, r.status);
        }
    } catch (Exception e) {
        System.err.println("ERROR: " + e.getMessage());
    }
}

private String formatBytes(long bytes) {
    if (bytes >= 1_073_741_824) return String.format("%.1f GB", bytes / 1e9);
    if (bytes >= 1_048_576)     return String.format("%.1f MB", bytes / 1e6);
    return bytes + " B";
}
}
