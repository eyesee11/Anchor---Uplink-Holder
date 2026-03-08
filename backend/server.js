require("dotenv").config();
const express = require("express");
const cors = require("cors");
const { google } = require("googleapis");
const path = require("path");
const CLIENT_ID = process.env.CLIENT_ID; 
const CLIENT_SECRET = process.env.CLIENT_SECRET; 
const REDIRECT_URI =
  process.env.REDIRECT_URI || "http://localhost:3000/auth/callback";
const PORT = process.env.PORT || 3000;

const SCOPES = ["https://www.googleapis.com/auth/drive.file"];

const app = express();

app.use(cors());
app.use(express.static(path.join(__dirname, "../website")));

function getOAuthClient() {
  return new google.auth.OAuth2(CLIENT_ID, CLIENT_SECRET, REDIRECT_URI);
}
app.get("/auth/login", (req, res) => {
  const client = getOAuthClient();
  const url = client.generateAuthUrl({
    access_type: "offline",
    prompt: "consent",
    scope: SCOPES,
  });
  res.redirect(url);
});
app.get("/auth/callback", async (req, res) => {
  const { code, error } = req.query;
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
    const { tokens } = await client.getToken(code);
    const storedCredential = {
      user: {
        accessToken: tokens.access_token,
        refreshToken: tokens.refresh_token,
        expirationTimeMilliseconds: tokens.expiry_date ?? null,
      },
    };
    const credJson = JSON.stringify(storedCredential, null, 2);
    res.send(successPage(credJson, tokens.refresh_token ? true : false));
  } catch (err) {
    console.error("Token exchange failed:", err.message);
    res.status(500).send(errorPage(`Token exchange failed: ${err.message}`));
  }
});
app.get("/download/jar", (req, res) => {
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
