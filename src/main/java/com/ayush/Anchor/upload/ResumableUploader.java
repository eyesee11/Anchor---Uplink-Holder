package com.ayush.Anchor.upload;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Paths;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ayush.Anchor.manifest.FileRecord;
import com.ayush.Anchor.manifest.ManifestStore;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.json.JsonHttpContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;

public class ResumableUploader {
    private static final Logger log = LoggerFactory.getLogger(ResumableUploader.class);
    private static final int    CHUNK_SIZE  = 8 * 1024 * 1024;   // 8 MB — must be multiple of 256 KB
    private static final String UPLOAD_URL  = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable";
    private final Drive driveService;
    private final ManifestStore manifest;

    public ResumableUploader(Drive driveService, ManifestStore manifest) {
        this.driveService = driveService;
        this.manifest     = manifest;
    }

    public String startOrResumeSession(FileRecord record) throws IOException, SQLException {
        if (record.resumeUri != null) {
            log.info("Resuming existing session for: {}", record.absolutePath);
            return record.resumeUri;
        }

        // Initiate a new resumable upload session via raw HTTP POST.
        // We can't use driveService.files().create(meta).setUploadType() because
        // setUploadType() is not available on Drive.Files.Create in this library version.
        // Instead we POST directly to the upload endpoint and read the Location header.
        com.google.api.services.drive.model.File meta = new com.google.api.services.drive.model.File();
        meta.setName(Paths.get(record.absolutePath).getFileName().toString());

        HttpRequest request = driveService.getRequestFactory()
            .buildPostRequest(
                new GenericUrl(UPLOAD_URL),
                new JsonHttpContent(GsonFactory.getDefaultInstance(), meta));
        request.getHeaders().set("X-Upload-Content-Type", "application/octet-stream");
        request.getHeaders().set("X-Upload-Content-Length", String.valueOf(record.sizeBytes));

        HttpResponse response = request.execute();
        String resumeUri = response.getHeaders().getLocation();
        manifest.updateProgress(record.absolutePath, 0, resumeUri);
        return resumeUri;
    }

    public void uploadFile(FileRecord record) throws IOException, SQLException {
        String resumeUri = startOrResumeSession(record);
        long offset = record.bytesUploaded;  // where to resume from

        try (RandomAccessFile raf = new RandomAccessFile(record.absolutePath, "r")) {
            raf.seek(offset);
            byte[] buffer = new byte[CHUNK_SIZE];
            long   totalSize = record.sizeBytes;

            while (offset < totalSize) {
                int bytesRead = raf.read(buffer);
                if (bytesRead == -1) break;

                long end = offset + bytesRead - 1;

                // PUT chunk to the resumable URI
                HttpRequest chunkRequest = driveService.getRequestFactory()
                    .buildPutRequest(new GenericUrl(resumeUri),
                        new ByteArrayContent("application/octet-stream",
                            buffer, 0, bytesRead));

                chunkRequest.getHeaders().setContentRange(
                    String.format("bytes %d-%d/%d", offset, end, totalSize));
                chunkRequest.execute();   // throws on non-2xx; response body is empty for intermediate chunks
                offset += bytesRead;

                manifest.updateProgress(record.absolutePath, offset, resumeUri);
                log.info("Uploaded {}/{} bytes ({}%)", offset, totalSize, 100 * offset / totalSize);
            }
        }

        manifest.markDone(record.absolutePath);
        log.info("Upload complete: {}", record.absolutePath);
    }
}