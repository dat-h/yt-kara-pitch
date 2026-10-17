# Kara Pitch

A karaoke player for Fire TV / Android TV. Singers queue YouTube videos from their phones
through a web page served by the TV; the TV plays them in order and can shift each song's key
by ±6 semitones without changing its speed.

## Using it
- Open the app on the TV. The idle screen shows a QR code and `http://<tv-ip>:8080`.
- On a phone on the same Wi-Fi, open that address, paste a YouTube link, add your name and
  (optionally) a key, and tap **Add to queue**.
- Or search on the TV: **Search songs** on the idle screen, or the remote's **Menu (☰)** key
  at any time (the song keeps playing). Pick a result, pick who's singing and the key, **Add**.
  Phones get the same search above the paste-link form.
- Songs start automatically after a 5-second "Up next" card (press OK to start now).

TV remote while a song plays:

| Button | Action |
|---|---|
| Up / Down | Key +1 / −1 semitone (−6…+6) |
| Left / Rewind | Back 10 s |
| Right / Fast-forward | Forward 10 s; **hold** to skip the song |
| OK / Play-Pause | Pause / resume |
| Back, twice | Stop this song |
| Menu (☰) | Open search (Back closes it; the remote navigates search while it's open) |

Each song starts at the key it was queued with (original if none). Leaving the app puts the
song that was playing back at the front of the queue. The queue lives in memory only.

## Build and install
Needs JDK 17+ and the Android SDK (`ANDROID_HOME` or `local.properties`).

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n dev.ytkara/.ui.MainActivity
```

## When YouTube playback breaks
Streams come from [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), which
needs updating whenever YouTube changes its site. If songs suddenly fail with "Couldn't load":

1. Find the latest tag on the NewPipeExtractor releases page.
2. Set `newpipeExtractor` in `gradle/libs.versions.toml` to it.
3. Rebuild and reinstall as above.

## HTTP API
All JSON, CORS open to any origin, so another frontend can drive the queue.

| Method | Path | Body | Result |
|---|---|---|---|
| GET | `/api/state?since=<v>&player=1` | | `{version, phase, upNext, nowPlaying, player, queue}`; `304` if `since` is current and `player` isn't asked for |
| GET | `/api/search?q=<text>&karaoke=1` | | `{results:[{url,title,channel,thumb,durationSec}]}` (≤20 videos, ≤15 min, no live); `karaoke=0` searches exactly |
| POST | `/api/queue` | `{url, singer?, key?, title?, channel?, thumb?, durationSec?}` | `201` + entry; `400` if not a YouTube link. With `title` (from a search result) the entry is `READY` at once |
| PATCH | `/api/queue/{id}` | `{singer?, key?}` | entry |
| POST | `/api/queue/{id}/move` | `{index}` | `204` |
| DELETE | `/api/queue/{id}` | | `204` |

`phase` is `idle`, `upnext` (with `upNext.secondsLeft`) or `playing`. Entry `status` is
`RESOLVING`, `READY` or `ERROR`.
