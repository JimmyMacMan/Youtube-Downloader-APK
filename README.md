# 📥 Android Media Downloader

A modern, open-source Android application built with **Jetpack Compose** (Material You) and powered by **`youtubedl-android`** and **`FFmpeg`** under the hood. It allows you to download videos up to 4K resolution, extract high-bitrate MP3 audio, and route files directly to your preferred folder or public storage.

---

## ✨ Features

- **High-Resolution Video:** Download resolutions up to 4K (2160p), 1080p, 720p, or 480p with automatic on-device DASH stream muxing.
- **MP3 Audio Extraction:** Extract and convert audio tracks directly into MP3 at your chosen bitrate (320 kbps, 192 kbps, etc.) via FFmpeg.
- **Share Sheet Integration:** Share any link straight from the YouTube app or mobile browser directly into the downloader.
- **Custom Download Location:** Choose any custom folder using Android's Storage Access Framework (SAF) or fall back to your public Downloads/Music directories.
- **Persistent Settings:** Automatically remembers your last-used format mode, quality preference, and save folder across app restarts.
- **In-App Engine Updates:** Update the internal `yt-dlp` extractor binary on the fly with a single tap.

---

## 📲 How to Install

### Option A: Build via GitHub Actions (Recommended for Mobile/Cloud)
1. Fork or clone this repository.
2. Go to the **Actions** tab in your GitHub repository.
3. Select the **Build Android APK** workflow and click **Run workflow**.
4. Once the build completes (usually ~3 minutes), scroll to the **Artifacts** section at the bottom of the summary page and download `MediaDownloader-APK.zip`.
5. Extract the `.zip` file, transfer `app-debug.apk` to your phone, and tap it to install. *(Make sure to allow "Install from unknown sources" if prompted).*

### Option B: Build Locally in Android Studio
1. Clone the repository to your computer.
2. Open the project in **Android Studio** (Ladybug or newer).
3. Navigate to **Build ➔ Build Bundle(s) / APK(s) ➔ Build APK(s)**.
4. Locate the compiled `app-debug.apk` in `app/build/outputs/apk/debug/` and install it on your device.

---

⚠️ Disclaimer
> **Vibe Coded**  
> This project was entirely built, structured, and iterated upon through conversational AI coding sessions ("vibe coded"). While it is fully functional and packs robust on-device extraction tooling, expect experimental rough edges, rapid architecture shifts, and spontaneous digital improvisation. Use and modify at your own discretion!