# SmartScan

A CamScanner-style document scanner for Android (Kotlin + Jetpack Compose + CameraX + OpenCV + ML Kit OCR).
Everything runs on-device, no account, no ads, no internet needed.

## Features
- **Live edge detection** in the camera, with an outline drawn over the document
- **Auto-capture** when the page is steady (toggle AUTO/MANUAL), waits for the next page before capturing again
- **Batch mode** (scan many pages in a row) or **Single mode** (goes straight to crop)
- **Manual crop** with 4 corner handles + 4 edge handles and a **magnifier loupe**, Auto re-detect, Full page
- **Perspective correction** (flattens tilted/angled shots)
- **Filters:** Original, Magic Color (shadow removal + vivid), Lighten, Grayscale, B&W (crisp text)
- Rotate, reorder (move left/right), delete pages, add more pages to an existing document
- **Import from gallery** (multiple photos at once)
- **OCR**: extract text from a page and copy it
- **Export:** PDF, **Searchable PDF (OCR text layer)**, JPG, PNG, TXT
- PDF page size A4 / Letter / Fit, quality High / Medium / Small (small PDFs: JPEGs are embedded directly)
- Share to any app, or save to `Download/SmartScan`
- Document list with search, rename, delete, quick "Share as PDF"

## Build with GitHub (no Android Studio needed)
1. Create a new GitHub repository and upload all files from this folder (keep the `.github` folder).
2. Go to the **Actions** tab. The **Build APK** workflow runs on every push (or press *Run workflow*).
3. When it finishes, open the run and download **SmartScan-apk** under *Artifacts*. Unzip and install the APK.

## Keep updates installable (recommended)
Without your own key, each build is signed with a different temporary key, so a new APK will not install over the old one (you'd have to uninstall first and lose your scans).
1. Actions → **Create signing key (run once)** → Run workflow → enter a password.
2. Download the **keystore-base64** artifact and open `KEYSTORE_BASE64.txt`.
3. Repo → Settings → Secrets and variables → Actions → add these secrets:
   - `KEYSTORE_BASE64` = the text from the file
   - `KEYSTORE_PASSWORD` = your password
   - `KEY_ALIAS` = `smartscan`
   - `KEY_PASSWORD` = your password
4. Re-run **Build APK**. All future builds use the same key.

## Tech notes
- minSdk 29 (Android 10+), arm64 only (keeps APK size down).
- Images are processed at up to 3000 px on the long side.
- Documents live in the app's private storage (`files/docs/<id>/`).
