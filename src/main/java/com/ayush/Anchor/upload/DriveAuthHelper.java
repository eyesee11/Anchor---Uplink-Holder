package com.ayush.Anchor.upload;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.DriveScopes;

import java.io.*;
import java.nio.file.*;
import java.util.List;

public class DriveAuthHelper {

    private static final String TOKENS_DIR  = System.getProperty("user.home") + "/.anchor/tokens";
    private static final String CREDENTIALS = System.getProperty("user.home") + "/.anchor/credentials.json";

/*
credentials.json is downloaded from Google Cloud Console (your OAuth 2.0 client configuration). 
TOKENS_DIR is where the auth library saves the access/refresh tokens after the user consents the first time — 
so they don't need to log in again every run.
*/

    public static com.google.api.services.drive.Drive buildDriveService() throws Exception {
        var transport = GoogleNetHttpTransport.newTrustedTransport();
        var jsonFactory = GsonFactory.getDefaultInstance();

/*
GoogleNetHttpTransport.newTrustedTransport() creates an HTTPS transport that trusts the Java default trusted certificates. 
GsonFactory is Google's JSON factory (they use Gson internally, distinct from Jackson which we use elsewhere).
*/        

        try (var reader = new FileReader(CREDENTIALS)) {
            var secrets = GoogleClientSecrets.load(jsonFactory, reader);

            var flow = new GoogleAuthorizationCodeFlow.Builder(
                    transport, jsonFactory, secrets,
                    List.of(DriveScopes.DRIVE_FILE))   // only access files we created
                .setDataStoreFactory(new FileDataStoreFactory(Paths.get(TOKENS_DIR).toFile()))
                .setAccessType("offline")   // get a refresh token
                .build();

/*
DriveScopes.DRIVE_FILE — principle of least privilege. We only request access to files Anchor itself creates, 
not the user's entire Drive. setAccessType("offline") requests a refresh token, 
which allows renewing the access token without user interaction after expiry.
*/
            var receiver = new com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver.Builder()
                .setPort(9876).build();

            var credential = new com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp(flow, receiver)
                .authorize("user");
                
/*
On first run, this opens the browser to accounts.google.com/o/oauth2/auth. After the user grants permission, 
Google redirects to localhost:9876 (the Jetty server we started), which captures the auth code and exchanges it for tokens. 
On subsequent runs, the stored refresh token is used silently.
*/
            return new com.google.api.services.drive.Drive.Builder(transport, jsonFactory, credential)
                    .setApplicationName("Anchor")
                    .build();
        }
    }
}
