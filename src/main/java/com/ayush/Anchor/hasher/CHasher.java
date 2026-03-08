package com.ayush.Anchor.hasher;

import com.fasterxml.jackson.databind.ObjectMapper; //Jackson's main class to parse JSON
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public class CHasher {
    private static final Logger log = LoggerFactory.getLogger(CHasher.class);
    private static final ObjectMapper objectMapper = new ObjectMapper(); //it's thread safe and expensive to create again and again

    private final String hasherBinaryPath;

    public CHasher(String hasherBinaryPath) {
        this.hasherBinaryPath = hasherBinaryPath;
    }

    public HashResult hash(Path filePath) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(hasherBinaryPath, filePath.toString());
        pb.redirectErrorStream(false);   // keep stdout and stderr separate
                Process process = pb.start();

        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
                if (exitCode != 0) {
            log.error("Hasher exited with code {}: {}", exitCode, output);
            throw new IOException("Hasher failed with exit code: " + exitCode);
        }

        return objectMapper.readValue(output, HashResult.class);
    }
}
