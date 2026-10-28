# Myo TV Remote

Control an Android TV (built for the NVIDIA Shield) with a Thalmic Labs Myo armband.
No PC and no Myo Connect: the app runs on the TV, talks to the armband directly over
Bluetooth LE, and turns gestures into remote-control key presses.

## How it works

- **Armband:** uses the Bluetooth protocol Thalmic Labs published as
  [`myohw.h`](https://github.com/thalmiclabs/myo-bluetooth). The armband's own onboard
  classifier recognises poses; the app also reads arm orientation for up/down.
- **Key presses:** Android doesn't let a normal app press keys in other apps. The app
  connects to the TV's own ADB daemon over localhost ("Network debugging") and starts a
  tiny helper, `InjectServer`, as the shell user with `app_process`, the same technique
  scrcpy uses. Key codes are streamed to it, so presses arrive almost instantly. If the
  helper can't start, it falls back to `input keyevent`, which is slower.

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

| Gesture | Action |
|---|---|
| Double tap | Unlock / lock. It relocks after 10 s without gestures |
| Wave left / right | D-pad left / right (corrected for left or right arm) |
| Raise / lower forearm | D-pad up / down; hold to repeat |
| Fist | Select |
| Fingers spread | Back; hold for 1.2 s for Home |

The armband gives a short buzz for each recognised action and a double buzz when it locks.
If raising your arm moves down, switch **Up/down** to inverted in the app.

## Notes

- Network debugging lets anything on your local network ask for ADB access. The TV still
  shows an approval prompt for each new computer, but turn it off if you stop using this app.
- Tested so far with unit tests only (protocol decoding and gesture logic). It hasn't been
  run against a real armband yet.
- Newer Google TV devices replace Network debugging with "Wireless debugging", which needs
  pairing over TLS. That isn't supported yet.

## Development

```
./gradlew testDebugUnitTest   # protocol and gesture logic tests
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Code layout (`app/src/main/java/dev/myotv/`):

- `MyoProtocol.kt`: UUIDs, commands and packet parsing from `myohw.h`
- `MyoConnection.kt`: BLE scan, connect, setup and notifications
- `GestureMapper.kt`: poses and orientation in, key presses and vibrations out
- `KeyInjector.kt` + `inject/InjectServer.java`: key delivery over localhost ADB
- `MyoService.kt`: foreground service tying them together
- `MainActivity.kt`: TV setup and status screen
