# Projector Guy App (Android Studio / WebView)

A native Android WebView app for the XBJ A5 Pro projector. It loads the Projector
Guy website **and** exposes a native `AndroidBridge` so the firmware-fix page
(`fix.html`) can detect a USB drive, format it to FAT32, and stream the 4 firmware
files straight onto it — automating the whole "Local Update" rescue.

This is the custom-app route. It does things a PWA Builder / TWA wrap **cannot**.

---

## Project layout

```
ProjectorGuyApp/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/projectorguy/app/
        │   ├── MainActivity.java     ← WebView + bridge wiring + permissions
        │   └── AndroidBridge.java    ← all native USB methods
        ├── assets/www/fix.html       ← firmware helper page (bundled copy)
        └── res/...                   ← theme, layout, launcher icon
```

`fix.html` is also published on the live website
(`https://karlbutlertts.github.io/xbj-apk-store/fix.html`) so the in-app link is
same-origin and the bridge is available to it.

---

## How to build

1. Open the `ProjectorGuyApp` folder in **Android Studio** (Giraffe or newer).
2. Let Gradle sync (it will download Gradle 8.2 + AGP 8.1.4). You need **JDK 17**.
3. Add the Gradle wrapper jar if Android Studio doesn't auto-create it:
   `gradle wrapper` from a terminal, or use Android Studio's bundled Gradle.
4. **Build → Build APK(s)** → `app/build/outputs/apk/debug/app-debug.apk`.
5. Sideload onto the projector (USB stick + a file manager, or `adb install`).

### Replace the launcher icon (optional)
A placeholder vector "XBJ" icon is included. To use the real PNG icon, add
`ic_launcher.png` into `res/mipmap-*` folders, or use Android Studio's
**Image Asset** wizard.

---

## ⚠️ The privilege requirement (read this)

`AndroidBridge.formatUsbFat32()` shells out to `umount`, `mkfs.fat` and `mount`.
A **normal installed APK is not allowed to run these** — Android blocks it.

For the auto-format to actually work, ONE of these must be true on the projector:

| Option | What it means |
|--------|---------------|
| **Rooted projector** | The app calls `su`/has root, so the shell commands succeed. Most XBJ A5 Pro units are not rooted out of the box. |
| **System app** | The APK is signed with the **platform/firmware signing key** and installed to `/system/priv-app`. Only possible if you have the device's signing keys (you almost certainly don't). |

If neither is true:
- USB **detection**, **space check**, **file streaming to USB** (`openWriteSessionRoot`
  + `appendChunk`) and **clearUsbRoot** still work *as long as the app has storage
  permission and the USB is already FAT32 / writable*.
- `formatUsbFat32()` returns `false`. `fix.html` handles this gracefully: it shows
  a message telling the user to format the stick to FAT32 on a computer first, then
  retry (it still clears the root and streams the files).

**Recommendation:** if the projectors aren't rooted, ship the app with the
"format on a computer first" fallback as the normal path, and treat auto-format as
a bonus that only fires on rooted units.

### Other caveats
- **CORS / large file:** `fix.html` streams the Google Drive files with `fetch()`.
  Cross-origin streaming reads can be blocked by the WebView's security policy and
  Google Drive's large-file interstitial. If the 1 GB `DY_8D196_026.bin` fails to
  stream, the cleaner fix is to add a native `downloadToUsb(url, filename)` method
  to `AndroidBridge` that uses `HttpURLConnection` / `DownloadManager` server-side
  (no CORS). Say the word and I'll add it.
- **MANAGE_EXTERNAL_STORAGE:** on Android 11+ the app asks for all-files access on
  first launch — the user must grant it for USB writes to work.
