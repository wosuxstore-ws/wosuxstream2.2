# FreeTV – free live TV for Android phones, tablets and Android TV boxes

Kotlin + Media3 (ExoPlayer). No ads, no accounts, no paid services. The app contains no channels itself:
it loads free, community-maintained M3U playlists of publicly available streams
([iptv-org](https://github.com/iptv-org/iptv)) and plays them. You can also load any M3U link of your own.

## Build the APK with GitHub Actions
1. Create a new GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab. The *Build APK* workflow starts on every push (or press **Run workflow**).
3. When it turns green, open the run and download **FreeTV-apk** (a zip containing `FreeTV.apk`).
4. Optional: push a tag such as `v1.0` and the APK is also attached to a **GitHub Release**
   (handy for downloading directly on the TV box).

The APK is signed with the key in `app/freetv.keystore`, so every new build installs over the previous one.
If your repo is public, anyone can see that key; that is fine for a personal app, but do not publish it on Google Play with it.

## Install
- **Phone/tablet:** open the APK, allow "install unknown apps" when asked.
- **Android box:** copy `FreeTV.apk` to a USB stick and open it with a file manager, or use the free
  *Downloader* app with your GitHub Release link, or `adb connect <box-ip>` then `adb install FreeTV.apk`.

## Controls
| | Remote | Touch |
|---|---|---|
| Browse | arrows, OK to play | scroll, tap |
| Favorite | hold OK on a channel | long-press |
| Next / previous channel (in player) | UP / DOWN (or CH+ / CH-) | Next / Prev buttons |
| Show / hide info | OK | tap screen |
| Back to list | BACK | back |

## Notes
- Free streams come and go. Some are geo-blocked or offline; use **Source** to pick another country/category,
  or **Refresh** to fetch the newest list.
- Playlists are cached on the device, so the list opens instantly and still shows offline.
- Minimum Android 5.0 (API 21).
