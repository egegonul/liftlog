# Lift Log (Android)

Workout and body-weight tracker with double-progression hints and charts.
Built by GitHub Actions; no Android Studio needed.

## One-time setup

1. **Create a repo** (e.g. `liftlog`) and push this folder to its `main` branch.
   A public repo makes the APK downloadable on your phone without logging in.

2. **Create a signing key** (once, on your computer; needs a JDK for `keytool`):

   ```bash
   keytool -genkeypair -v -keystore liftlog.jks -alias liftlog \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
   Use the same password for the keystore and the key.
   **Back up `liftlog.jks` and the password somewhere safe.** If you lose it, you
   can't update the app anymore without uninstalling (which deletes data).
   Never commit it (it's in `.gitignore`).

3. **Add 4 repo secrets** (Settings → Secrets and variables → Actions), or with `gh`:

   ```bash
   gh secret set KEYSTORE_BASE64 --body "$(base64 -w0 liftlog.jks)"
   gh secret set KEYSTORE_PASSWORD --body 'your-password'
   gh secret set KEY_PASSWORD      --body 'your-password'
   gh secret set KEY_ALIAS         --body 'liftlog'
   ```

4. **Run the build**: push to `main` (or Actions → Build APK → Run workflow).

## Install / update on your phone

Download: `https://<your-user>.github.io/liftlog/liftlog.apk`

The release asset is also at `https://github.com/<your-user>/liftlog/releases/download/latest/liftlog.apk`. Chrome on Android often freezes at 100% on that link, so use the github.io link above.

Open it and allow "install unknown apps" for your browser when asked.
To update, download the same link again and install over the old app. Your data stays.

## Why your data survives updates

- Data is stored in the app's private storage as a versioned JSON file, written atomically.
- Every build uses **the same signing key** and **the same applicationId**, and
  `versionCode` auto-increments (the Actions run number). Those three are what let
  Android install an update in place. Don't change `applicationId`, don't swap the key,
  and don't rename the workflow file (that resets the run number).
- When adding features that change the data format: bump `SCHEMA` in `Data.kt` and
  add a step in `migrate()`. Old data is upgraded, never thrown away.
- If the data file is ever unreadable, it's copied aside instead of overwritten.
- Android Auto Backup is on, and **⋮ → Export backup** saves a JSON file you control.
  **⋮ → Import backup or CSV** restores it (a copy of current data is kept first).

## Moving data from the web version

In the web app, tap **Export CSV**, then in this app use **⋮ → Import backup or CSV**.
Imported exercises default to an 8–12 rep range. Tap **Edit** to adjust.
