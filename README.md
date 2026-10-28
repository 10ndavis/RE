# Myo TV Remote

Control an Android TV (built for the NVIDIA Shield) with a Thalmic Labs Myo armband.
No PC and no Myo Connect: the app runs on the TV, talks to the armband directly over
Bluetooth LE, and turns gestures into remote-control key presses.

## How it works

- **Armband:** uses the Bluetooth protocol Thalmic Labs published as
  [`myohw.h`](https://github.com/thalmiclabs/myo-bluetooth). The armband's own onboard
  classifier recognises poses; the app also reads arm orientation for up/down and the pointer.
- **Input:** Android doesn't let a normal app press keys or touch the screen in other apps.
  The app connects to the TV's own ADB daemon over localhost ("Network debugging") and
  starts a tiny helper, `InjectServer`, as the shell user with `app_process`, the same
  technique scrcpy uses. Commands are streamed to it, so input arrives almost instantly.
  If the helper can't start, it falls back to the slower `input` command, without live dragging.
- **Mouse pointer:** Android TV has no system cursor an app can drive, so mouse mode draws
  its own pointer over other apps and sends touches where it points. The draw-over-apps
  permission is granted automatically over the same ADB connection.

## Install

1. Build (`./gradlew assembleRelease`) or use a provided APK.
2. Sideload it onto the Shield, for example with `adb install app-release.apk` from a
   computer, or with a file manager app on the TV.

## One-time setup on the TV

1. **Settings > Device Preferences > About**: select **Build** 7 times to enable Developer options.
2. **Settings > Device Preferences > Developer options**: turn on **Network debugging**.
3. Open **Myo TV Remote**, press **Start** and allow Bluetooth/location access.
4. When the TV asks to allow network debugging, tick **Always allow** and press **OK**.
5. Put the armband on, wait a minute for it to warm up, then do the sync gesture
   (wave out and hold) until it vibrates.

Press **Test: press Down** to check that key presses reach the TV. The app restarts
itself after a reboot if it was running.

## Gestures

Switch between the two modes with the **Mode** button in the app.

**D-pad mode** (default)

| Gesture | Action |
|---|---|
| Double tap | Unlock / lock. It relocks after 10 s without gestures |
| Wave left / right | D-pad left / right (corrected for left or right arm) |
| Raise / lower forearm | D-pad up / down; hold to repeat |
| Fist | Select |
| Fingers spread | Back; hold for 1.2 s for Home |

**Mouse pointer mode**

| Gesture | Action |
|---|---|
| Double tap | Unlock, then click. It relocks after 10 s without arm movement |
| Point arm | The pointer goes where you point, like a Wii remote |
| Fist | Press and hold at the pointer: hold still to long-press (right click), move to drag or scroll |
| Wave left / right | D-pad left / right |
| Fingers spread | Back; hold for 1.2 s for Home |

Pointing is absolute: the direction your arm points when you unlock becomes the middle of
the screen, and about 40° of side-to-side swing (22° up and down) covers the whole screen.
Pointing past an edge drags the middle along with you, which also corrects the armband's
slow compass drift; locking and unlocking re-centers completely. The pointer appears once
the armband is synced, faded while gestures are locked.
The armband gives a short buzz for each recognised action and a double buzz when it locks.
If raising your arm moves down, switch **Up/down** to inverted. If the pointer moves the
wrong way sideways, switch **Pointer left/right** to inverted.

## Notes

- Network debugging lets anything on your local network ask for ADB access. The TV still
  shows an approval prompt for each new computer, but turn it off if you stop using this app.
- Tested so far with unit tests only (protocol decoding, D-pad and mouse logic). It hasn't been
  run against a real armband yet.
- Newer Google TV devices replace Network debugging with "Wireless debugging", which needs
  pairing over TLS. That isn't supported yet.

## Development

```
./gradlew testDebugUnitTest   # protocol, D-pad and mouse mode tests
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Code layout (`app/src/main/java/dev/myotv/`):

- `MyoProtocol.kt`: UUIDs, commands and packet parsing from `myohw.h`
- `MyoConnection.kt`: BLE scan, connect, setup and notifications
- `GestureMapper.kt`: poses and orientation in, key presses, pointer moves, touches and vibrations out
- `InputInjector.kt` + `inject/InjectServer.java`: key and touch delivery over localhost ADB
- `PointerOverlay.kt`: the on-screen mouse pointer
- `MyoService.kt`: foreground service tying them together
- `MainActivity.kt`: TV setup and status screen
