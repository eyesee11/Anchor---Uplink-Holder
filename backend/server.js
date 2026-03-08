// backend/server.js — Node.js OAuth proxy for Anchor
//
// WHY this exists:
//   credentials.json contains our Google OAuth client secret.
//   Publishing it publicly lets anyone abuse our Google Cloud quota.
//   This server stores the secret as environment variables — it never appears
//   in any public file. Users authenticate through this server and receive
//   only a token file — the secret never leaves the server.
//
// ── Full auth flow ───────────────────────────────────────────────────────────
//   1. User visits website, clicks "Authenticate with Google"
//   2. Browser calls GET /auth/login → we redirect to Google's consent page
//   3. User logs in and approves the app
//   4. Google redirects to GET /auth/callback?code=...
//   5. We exchange the code for access + refresh tokens
//   6. We send back an HTML page that auto-downloads a "StoredCredential" file
//   7. User places that file in ~/.anchor/tokens/StoredCredential
//   8. The Anchor JAR reads it — no browser login needed on every run
// ────────────────────────────────────────────────────────────────────────────

// ── Load environment variables ───────────────────────────────────────────────
require("dotenv").config();
// dotenv reads a .env file and adds each line to process.env.
// So if .env contains CLIENT_ID=abc, then process.env.CLIENT_ID === "abc".
// We call this FIRST before using any env vars below.
// Never commit .env to git — use .env.example as a template for others.

// ── Import dependencies ──────────────────────────────────────────────────────
const express = require("express");
// Express is the most popular Node.js HTTP server framework.
// It handles routing (match a URL → run a function), middleware, and responses.
// app.listen(3000) starts the server; app.get("/path", fn) registers a route.

const cors = require("cors");
// Browsers block "cross-origin" requests unless the server allows them.
// A cross-origin request is when JavaScript on page A calls an API on domain B.
// cors() adds the HTTP header "Access-Control-Allow-Origin: *" to every response,
// which tells the browser "any website is allowed to call this API".

const { google } = require("googleapis");
// Google's official Node.js client library for all Google APIs.
// We use google.auth.OAuth2 to:
//   - Build the URL the user visits to grant permission (generateAuthUrl)
//   - Exchange an auth code for real tokens (getToken)

const path = require("path");
// Node.js built-in module for working with file system paths.
// path.join("a", "b", "c") works correctly on Windows (\) and Linux (/).
// path.join(__dirname, "../website") = the folder one level up from this file, inside /website.

// ── Configuration ────────────────────────────────────────────────────────────
const CLIENT_ID = process.env.CLIENT_ID; // Your Google OAuth client ID
const CLIENT_SECRET = process.env.CLIENT_SECRET; // Your Google OAuth client secret
// NEVER hardcode these — always env vars

// The URL Google redirects to after the user logs in.
// MUST exactly match a URI registered in:
//   Google Cloud Console → APIs & Services → Credentials → your OAuth client → Authorized Redirect URIs
// For local dev: http://localhost:3000/auth/callback
// For production: https://your-deployed-domain.com/auth/callback
const REDIRECT_URI =
  process.env.REDIRECT_URI || "http://localhost:3000/auth/callback";

// The port the server listens on.
// process.env.PORT is set automatically by Railway, Render, Fly.io, etc.
// Falls back to 3000 for local development.
const PORT = process.env.PORT || 3000;

// The Google API scopes (permissions) we request from the user.
// DRIVE_FILE = only access files Anchor creates — never the user's pre-existing Drive files.
// This follows the "principle of least privilege" — ask for only what you need.
const SCOPES = ["https://www.googleapis.com/auth/drive.file"];

// ── App setup ────────────────────────────────────────────────────────────────
const app = express();

app.use(cors());
// Allows any webpage to call our API.
// In production you could restrict this: cors({ origin: "https://your-website.com" })

app.use(express.static(path.join(__dirname, "../website")));
// Serve all files inside the /website folder as public static files.
// GET / → sends website/index.html
// GET /Anchor-1.0-SNAPSHOT.jar → streams the JAR file directly
// This means you only deploy ONE thing (this backend) to serve everything.

// ── Helper function ──────────────────────────────────────────────────────────
function getOAuthClient() {
  // google.auth.OAuth2(clientId, clientSecret, redirectUri) creates a client
  // that can build OAuth URLs and exchange auth codes for tokens.
  // We create a fresh instance per request — avoids sharing state between users.
  return new google.auth.OAuth2(CLIENT_ID, CLIENT_SECRET, REDIRECT_URI);
}

// ── Route 1: GET /auth/login ─────────────────────────────────────────────────
// The "Authenticate with Google" button on the website links here.
// We build the Google consent page URL and redirect the browser directly to it.
// The user never sees this URL — the browser is just redirected immediately.
app.get("/auth/login", (req, res) => {
  const client = getOAuthClient();

  // generateAuthUrl() builds the full URL for Google's OAuth consent screen.
  //
  // access_type: "offline"
  //   Includes a refresh_token in the response. A refresh_token never expires
  //   and lets us silently get new access_tokens when the current one expires.
  //   Without this, the user would need to re-login every hour.
  //
  // prompt: "consent"
  //   Always shows the consent screen. Without this, Google skips the screen
  //   on repeat logins AND stops returning a refresh_token. We need the
  //   refresh_token every time, so we force the screen.
  //
  // scope: SCOPES
  //   The permissions we're requesting.
  const url = client.generateAuthUrl({
    access_type: "offline",
    prompt: "consent",
    scope: SCOPES,
  });

  // res.redirect(url) sends HTTP 302 with a Location header pointing to Google.
  // The browser automatically follows it — the user ends up at Google's login page.
  res.redirect(url);
});

// ── Route 2: GET /auth/callback ──────────────────────────────────────────────
// Google redirects the browser here after the user logs in.
// URL will look like: /auth/callback?code=4/P7q7W91a...&scope=https://...
// We extract the code, exchange it for tokens, then return a token file.
app.get("/auth/callback", async (req, res) => {
  const { code, error } = req.query;
  // req.query parses the URL's query string into an object.
  // For /auth/callback?code=abc&error=xyz, req.query = { code: "abc", error: "xyz" }

  // If the user clicked "Cancel" or something went wrong, Google sends ?error=access_denied
  if (error) {
    return res
      .status(400)
      .send(errorPage(`Google returned an error: ${error}`));
  }

  if (!code) {
    return res
      .status(400)
      .send(errorPage("Missing auth code in callback URL."));
  }

  try {
    const client = getOAuthClient();

    // client.getToken(code) makes a POST request to https://oauth2.googleapis.com/token
    // with: code, client_id, client_secret, redirect_uri, grant_type="authorization_code"
    // Google validates everything and responds with:
    //   { access_token, refresh_token, scope, token_type, expiry_date }
    //
    // access_token  = short-lived (1 hour), used for actual API calls
    // refresh_token = long-lived, used to silently get new access_tokens
    const { tokens } = await client.getToken(code);

    // ── Token format conversion ──────────────────────────────────────────────
    // The Node.js googleapis library uses snake_case: access_token, refresh_token, expiry_date
    // Java's FileDataStoreFactory expects camelCase: accessToken, refreshToken, expirationTimeMilliseconds
    //
    // The file must be named exactly "StoredCredential" (no extension) and placed at:
    //   ~/.anchor/tokens/StoredCredential
    // The Java app's GoogleAuthorizationCodeFlow reads this exact file and key ("user").
    const storedCredential = {
      user: {
        accessToken: tokens.access_token,
        refreshToken: tokens.refresh_token,
        expirationTimeMilliseconds: tokens.expiry_date ?? null,
      },
    };

    // JSON.stringify(obj, null, 2) = convert to JSON string with 2-space indentation.
    // The second arg (null) means "no replacer". The third arg (2) = indent level.
    const credJson = JSON.stringify(storedCredential, null, 2);

    // Embed the JSON directly in the success page and trigger a download via JS.
    // We use encodeURIComponent() to safely embed the JSON in a data: URI.
    // The browser's <a download> trick lets us name the saved file "StoredCredential".
    res.send(successPage(credJson, tokens.refresh_token ? true : false));
  } catch (err) {
    console.error("Token exchange failed:", err.message);
    res.status(500).send(errorPage(`Token exchange failed: ${err.message}`));
  }
});

// ── Route 3 & 4: File downloads via backend routes ───────────────────────────
// These are alternate download routes that go through the server instead of
// the static file server. They exist so credentials.json (if you add it) can
// be served from here without being in the public static folder.

app.get("/download/jar", (req, res) => {
  // res.download(filePath, fileName) sets Content-Disposition: attachment
  // and streams the file. The browser saves it as fileName.
  const file = path.join(__dirname, "../website/Anchor-1.0-SNAPSHOT.jar");
  res.download(file, "Anchor-1.0-SNAPSHOT.jar", (err) => {
    if (err) res.status(404).send("JAR not found. Run mvn package first.");
  });
});

app.get("/download/hasher", (req, res) => {
  const file = path.join(__dirname, "../website/hasher.exe");
  res.download(file, "hasher.exe", (err) => {
    if (err) res.status(404).send("hasher.exe not found.");
  });
});

// ── HTML helpers ─────────────────────────────────────────────────────────────

// Returns a styled success page that auto-downloads the StoredCredential file
function successPage(credJson, hasRefreshToken) {
  const warning = !hasRefreshToken
    ? `<p style="color:#ffb700;margin-top:12px;">
        ⚠ No refresh_token received. This can happen if you already authorized
        this app. <a href="/auth/login" style="color:#00ff41;">Re-authenticate</a>
        and click "Allow" again to get a fresh token.
       </p>`
    : "";

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <title>Anchor — Authentication Successful</title>
  <style>
    body { background:#000; color:#00ff41; font-family:"Courier New",monospace;
           display:flex; flex-direction:column; align-items:center;
           justify-content:center; min-height:100vh; padding:40px; }
    h1  { font-size:1.4rem; margin-bottom:16px; }
    p   { color:#ccc; font-size:0.9rem; line-height:1.8; max-width:600px; text-align:center; }
    code { color:#00ff41; background:#111; padding:2px 8px; }
    .steps { text-align:left; max-width:600px; margin-top:24px; color:#ccc; font-size:0.85rem; line-height:2; }
    .step-num { color:#ffb700; }
    a { color:#00ff41; }
    .btn { margin-top:24px; display:inline-block; padding:12px 28px;
           background:#00ff41; color:#000; font-family:inherit;
           font-size:0.85rem; letter-spacing:2px; text-decoration:none;
           cursor:pointer; border:none; font-weight:bold; }
  </style>
</head>
<body>
  <h1>&gt; Authentication Successful</h1>
  <p>Your <code>StoredCredential</code> file is downloading automatically.</p>
  ${warning}
  <div class="steps">
    <div><span class="step-num">01</span> &nbsp;A file named <code>StoredCredential</code> just downloaded</div>
    <div><span class="step-num">02</span> &nbsp;Create the tokens folder: <code>mkdir $env:USERPROFILE\\.anchor\\tokens</code></div>
    <div><span class="step-num">03</span> &nbsp;Move the file: <code>Move-Item StoredCredential $env:USERPROFILE\\.anchor\\tokens\\StoredCredential</code></div>
    <div><span class="step-num">04</span> &nbsp;Run Anchor — no browser login needed!</div>
  </div>
  <a href="/" class="btn">&gt; Back to Website</a>

  <script>
    // Build a Blob from the credential JSON and trigger a browser download.
    // This avoids embedding secrets in the page URL or visible HTML source.
    const json = ${JSON.stringify(credJson)};
    const blob = new Blob([json], { type: "application/json" });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement("a");
    a.href     = url;
    a.download = "StoredCredential";   // exact filename the Java app expects
    document.body.appendChild(a);
    a.click();
    // Clean up the object URL after a short delay
    setTimeout(() => URL.revokeObjectURL(url), 5000);
  </script>
</body>
</html>`;
}

// Returns a styled error page
function errorPage(message) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <title>Anchor — Auth Error</title>
  <style>
    body { background:#000; color:#ff5555; font-family:"Courier New",monospace;
           display:flex; flex-direction:column; align-items:center;
           justify-content:center; min-height:100vh; padding:40px; }
    h1 { font-size:1.4rem; margin-bottom:16px; }
    p  { color:#ccc; font-size:0.9rem; max-width:500px; text-align:center; }
    a  { color:#00ff41; }
  </style>
</head>
<body>
  <h1>&gt; Authentication Failed</h1>
  <p>${message}</p>
  <p><a href="/auth/login">&gt; Try again</a></p>
</body>
</html>`;
}

// ── Start server ─────────────────────────────────────────────────────────────
app.listen(PORT, () => {
  console.log(`> Anchor backend running on http://localhost:${PORT}`);
  console.log(`> Website:    http://localhost:${PORT}/`);
  console.log(`> Auth login: http://localhost:${PORT}/auth/login`);
  if (!CLIENT_ID || !CLIENT_SECRET) {
    console.warn(
      "WARNING: CLIENT_ID or CLIENT_SECRET is not set in .env — auth will fail.",
    );
  }
});
