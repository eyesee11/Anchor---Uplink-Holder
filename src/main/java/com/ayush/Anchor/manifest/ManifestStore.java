package com.ayush.Anchor.manifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*; // for Connection, PreparedStatement, ResultSet 
import java.util.ArrayList;
import java.util.List;


public class ManifestStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ManifestStore.class);
    private static final String DB_FILE = System.getProperty("user.home") + "/.anchor/manifest.db";
    private final Connection conn;


    public ManifestStore() throws SQLException {
        Path dbPath = Paths.get(DB_FILE);
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (java.io.IOException e) {
            throw new RuntimeException("Cannot create ~/.anchor/ directory", e);
        }

        conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
        conn.setAutoCommit(true);   // each statement commits immediately
        initSchema();
}


//logs and store the file's specs into the resume_uri
private void initSchema() throws SQLException {
    String sql = """
        CREATE TABLE IF NOT EXISTS files (
            id             INTEGER PRIMARY KEY AUTOINCREMENT,
            absolute_path  TEXT    NOT NULL UNIQUE,
            size_bytes     INTEGER NOT NULL,
            sha256         TEXT    NOT NULL,
            status         TEXT    NOT NULL DEFAULT 'PENDING',
            bytes_uploaded INTEGER NOT NULL DEFAULT 0,
            resume_uri     TEXT
        )
        """;
    try (Statement st = conn.createStatement()) {
        st.execute(sql);
    }
}

//queues the file for resumable uplaod
public void insertFile(FileRecord record) throws SQLException {
String sql = "INSERT OR IGNORE INTO files (absolute_path, size_bytes, sha256, status) VALUES (?,?,?,?)";
try (PreparedStatement ps = conn.prepareStatement(sql)) {
    ps.setString(1, record.absolutePath);
    ps.setLong  (2, record.sizeBytes);
    ps.setString(3, record.sha256);
    ps.setString(4, record.status.name());
    ps.executeUpdate();
}
}


//main point of Anchor (Called after every uploaded chunk. Saving bytesUploaded + resumeUri to disk)
public void updateProgress(String absolutePath, long bytesUploaded, String resumeUri) throws SQLException {
    String sql = "UPDATE files SET bytes_uploaded=?, resume_uri=?, status='UPLOADING' WHERE absolute_path=?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setLong  (1, bytesUploaded);
        ps.setString(2, resumeUri);
        ps.setString(3, absolutePath);
        ps.executeUpdate();
    }
}


//succesfully uploaded the file, removes it from the resume_uri, cuz no longer needed gng
public void markDone(String absolutePath) throws SQLException {
    String sql = "UPDATE files SET status='DONE', bytes_uploaded=size_bytes, resume_uri=NULL WHERE absolute_path=?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, absolutePath);
        ps.executeUpdate();
    }
}


//if a file fails mid upload, it logs it and tthe the user can retry the upload
public void markError(String absolutePath, String reason) throws SQLException {
    String sql = "UPDATE files SET status='ERROR' WHERE absolute_path=?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, absolutePath);
        ps.executeUpdate();
    }
    log.error("File marked ERROR: {} — {}", absolutePath, reason);
}

//to print the table of status of files and uploads.
public List<FileRecord> getAll() throws SQLException {
    List<FileRecord> result = new ArrayList<>();
    String sql = "SELECT absolute_path, size_bytes, sha256, status, bytes_uploaded, resume_uri FROM files ORDER BY id";
    try (Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery(sql)) {
        while (rs.next()) {
            FileRecord r = new FileRecord(
                rs.getString("absolute_path"),
                rs.getLong  ("size_bytes"),
                rs.getString("sha256"),
                FileRecord.Status.valueOf(rs.getString("status"))
            );
            r.bytesUploaded = rs.getLong("bytes_uploaded");
            r.resumeUri     = rs.getString("resume_uri");
            result.add(r);
        }
    }
    return result;
}

@Override
public void close() throws SQLException {
    if (conn != null && !conn.isClosed()) {
        conn.close();
    }
}


}
