# Zune HD Launcher

An Android home screen that recreates the Zune HD's interface, with **Tidal** playback built in
so music feels like part of the device the way it did on the Zune.

<p>
<img src="docs/home.jpg" width="220" alt="Home menu">
<img src="docs/music.jpg" width="220" alt="Music pivot">
<img src="docs/nowplaying.jpg" width="220" alt="Now playing">
</p>

(Rendered headlessly before notification access was granted, so no song is loaded in these.)

## What's recreated

| Zune HD | Here |
|---|---|
| Main menu of huge lowercase words running off the right edge | Home: music, videos, pictures, radio, podcasts, marketplace, social, internet, apps, settings |
| Quickplay (history, pins) just to the right of the menu | Swipe left on home: album art tiles of recently played Tidal songs, pinned apps |
| Pivot headers ("artists albums songs …") that slide with the content | Music: queue · history · library · search; apps: apps · games |
| Now playing: art over imagery, giant artist name drifting across, tap for controls | Same, with Ken Burns panning on blurred art, scrubber, previous / play / next, swipe for next/previous, pull down to close |
| Media button overlay with song, transport and volume | Tap or pull down the status strip, or press a volume key |
| (new) news | The Google Discover feed, slid in over home like on the Pixel Launcher; dragging right on the menu pulls it in too |
| Tilt on press, staggered slide transitions | On every list item and menu word |

The full layout reconstruction, with sizes in Zune pixels, is in [docs/ZUNE_HD_UI.md](docs/ZUNE_HD_UI.md).

## How Tidal is embedded

Tidal has no public control API on Android, but like every player it publishes a **MediaSession**.
With notification access, the launcher reads that session and drives it directly:

- track, artist, album, art, position and the play queue (`MediaController`)
- play/pause, skip, seek and jumping to any queue item
- Tidal's own extra buttons (favourite, shuffle, repeat, …) from the session's custom actions,
  drawn with Tidal's icons
- **search** and **history** start playback with `playFromSearch`, the request Google Assistant uses
- when Tidal isn't running, play is sent to Tidal's media button receiver, so it resumes in the
  background; the app is only opened if that fails
- **library** tries Tidal's `MediaBrowserService` (what Android Auto uses). Apps often only accept
  callers they recognise; if Tidal refuses, the page says so and the other pages still work.

If something else is playing while Tidal is paused, the launcher follows that player instead
(turn off **follow other players** in settings to stick to Tidal). Any other music app can be
chosen under **music app**.

## Google feed (news)

The Pixel Launcher doesn't draw the Discover feed itself: the Google app attaches to the
launcher's window through its "launcher overlay" service and draws the feed over it.
`FeedClient.kt` speaks that service's binder protocol, the same one Launcher3 and Lawnchair use.

The Google app decides which launchers it serves. Launchers outside Google's list have generally
needed a **debuggable build**, so if **news** opens the Google app instead of sliding the feed
in, install the debug APK (`./gradlew :zune:assembleDebug`,
`zune/build/outputs/apk/debug/zune-debug.apk`). This hasn't been tried on a device yet.

## Install and set up

1. `./gradlew :zune:assembleRelease`, then install `zune/build/outputs/apk/release/zune-release.apk`.
2. Open it and tap **connect tidal** to grant notification access.
   On Android 13+ a sideloaded app may show "restricted setting": open the app's **App info**,
   use the ⋮ menu and **Allow restricted settings**, then try again.
3. **settings > default home** to make it your home screen.
4. Long-press **videos**, **radio**, **podcasts**, **social**, … to choose the app behind them.
   Long-press an app in **apps** to pin it to quickplay.

### Font

The Zune used Zegoe UI, which can't be distributed. The launcher uses Android's light sans-serif
until you add a font you have: copy `light.ttf` and `regular.ttf` into
`Android/data/dev.zunehd/files/fonts/` (or `zune/src/main/assets/fonts/` before building).
Microsoft's open-source Selawik (Segoe UI metric-compatible) is a good choice.

## Status

- Unit tested: session choice, history, time formatting and pivot motion
  (`./gradlew :zune:testDebugUnitTest`). Screens were checked by rendering them with Robolectric.
- Not yet run on a phone with Tidal, so how much Tidal exposes (custom actions, queue, whether
  it accepts library browsing and searches) is unconfirmed.

## Code (`src/main/java/dev/zunehd/`)

- `Player.kt`: the embedded player: picks and follows Tidal's media session, commands, art, history
- `MediaListener.kt`: the notification listener that unlocks media sessions
- `HomeActivity.kt`: main menu and quickplay
- `FeedClient.kt`: the Google feed overlay connection
- `MusicActivity.kt`, `LibraryPage.kt`: queue, history, library, search
- `NowPlayingView.kt`: the now playing screen, fully custom drawn
- `PivotView.kt`, `Views.kt`, `Ui.kt`: pivot, status strip, quick controls, mini player, backdrop, tilt, motion
- `Logic.kt`: Android-free logic covered by unit tests
