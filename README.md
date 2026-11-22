# Audio Only

A tiny Android app: paste a video, song or playlist link and it plays **just the audio**
(no video, no ads), with play/pause, next/previous, seek bar, shuffle and repeat.
Music keeps playing with the screen off, and you get controls in the notification / lock screen.

## What links work

| Link | Works? |
|---|---|
| YouTube video / playlist | Yes (see "If a site stops working") |
| SoundCloud, Bandcamp, PeerTube | Yes |
| Direct links (`.mp3`, `.m4a`, radio `.m3u8`, ...) | Yes |
| Spotify, Apple Music, JioSaavn, Netflix, ... | No - those streams are protected, no app can pull them from a link |

You can also use **Share** inside YouTube / SoundCloud and pick "Audio Only".

## Get the APK (no Android Studio needed)

1. Create a new empty repository on GitHub.
2. Push this folder to it:
   ```
   git init
   git add .
   git commit -m "Audio Only app"
   git branch -M main
   git remote add origin https://github.com/YOUR-USERNAME/YOUR-REPO.git
   git push -u origin main
   ```
3. On GitHub open the **Actions** tab. The "Build APK" run takes about 5-8 minutes.
4. Open the finished run, scroll to **Artifacts**, download **AudioOnly-apk** (a zip), unzip it.
5. Copy `app-release.apk` to your phone and tap it. Allow "install unknown apps" if Android asks.

If the run turns red, open it, copy the red error text and send it to Claude to get it fixed.

## Open it in Android Studio instead

File > Open > pick this folder, wait for the sync, then Build > Build APK(s)
(or run `gradle assembleRelease`). The APK appears in `app/build/outputs/apk/release/`.

## If a site stops working

YouTube and other sites change often. The fix is usually to update the extractor library:
in `app/build.gradle.kts` change `v0.26.5` to the newest version number from
https://github.com/TeamNewPipe/NewPipeExtractor/releases and build again.

## How it works (short)

- `MainActivity.kt` - the screen (plain Android views, so the app stays small)
- `PlaybackService.kt` - the player; keeps playing in the background
- `LinkResolver.kt` - turns a link into tracks, then into the real audio stream right before play
- `AppDownloader.kt` - lets the extractor download web pages

## Notes

- The APK is signed with the automatic debug key, which is fine for your own phone.
- NewPipeExtractor is GPLv3, so if you share this APK with others, share the source code too.
- Check each site's terms of use; this is meant for personal listening.
