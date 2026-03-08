package com.ayush.Anchor.manifest;

public class FileRecord {
    public enum Status { PENDING, UPLOADING, DONE, ERROR }

    public final String  absolutePath;   // C:\Videos\movie.mp4
    public final long    sizeBytes;      // 1_572_864  (1.5 MB)
    public final String  sha256;         // "a3f1c2..."
    public       Status  status;         // mutable — changes over time
    public       long    bytesUploaded;  // how far we got (for resume)
    public       String  resumeUri;      // Google's resumable upload URL

        public FileRecord(String absolutePath, long sizeBytes, String sha256, Status status) {
        this.absolutePath = absolutePath;
        this.sizeBytes    = sizeBytes;
        this.sha256       = sha256;
        this.status       = status;
        this.bytesUploaded = 0;
        this.resumeUri    = null;
    }

}
