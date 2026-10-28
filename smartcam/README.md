# SmartCam

An Android camera that watches what you're pointing at and coaches you to a better photo:
it finds the subject, tells you which way to move the phone to frame it well, keeps the
horizon level, and tunes focus, exposure and shutter speed for the scene. Everything runs
on the phone; no network needed.

## What it does

**Finds the subject.** On-device ML Kit models detect faces and prominent objects every frame,
and label the scene (food, sky, mountain, dog…) every few frames. The most prominent face wins,
otherwise the biggest, most central object. **Tap anything to lock onto it**: it's followed
as it moves (gold brackets). Tap an empty spot to just focus there.

**Works out the shot.** The scene type (Portrait, Group, Pet, Food, Close-up, Landscape, Night)
decides the composition:

| Scene | Where the subject goes |
|---|---|
| Portrait | Eyes on the top third line; face on the third *opposite* to where they're looking, so they look into the frame; centred when it's a close headshot |
| Group | Everyone in, faces around the top 40%; "step back" if someone is cut off |
| Moving subject | On the third behind it, so it moves into the frame |
| Landscape | Horizon on a third line, never across the middle |
| Large subject / food close-up | Centred |
| Anything else | Nearest rule-of-thirds point |

**Tells you where to point.** A target circle shows where the subject should be, with an arrow
from where it is now, plus plain advice: "Pan right →", "Tilt up ↑", "Move closer", "Step back",
"Level the phone ↺", "Hold steady…", then "Perfect, take the shot" (the shutter turns green).
A level line uses the gravity sensor. It works held upright or sideways: advice and text turn to
match.

**Adjusts the camera.**
- *Focus, metering and white balance* follow the subject, re-aimed when it changes or moves.
- *Exposure compensation* nudges the subject to a good brightness for the scene (a bit brighter
  for faces and food, darker for night so it still looks like night, brighter for snow and beach
  which otherwise come out grey) and pulls back when highlights blow out.
- *Shutter speed:* when the subject moves (and you aren't panning), it switches to manual exposure
  with a shutter fast enough to freeze it (1/125 to 1/500 s), raising ISO to keep the brightness.
  Shaky hands get a high fixed frame rate to cap the shutter; a steady phone in low light is
  allowed a slower shutter for a cleaner picture.
- *Flash* goes to auto only for people in the dark.

The bottom line shows what it's doing: scene, shutter, ISO, EV and a framing score.

**Enhances the photo** (toggle at top left). After capture, an auto edit stretches the tones to use
the full range, lifts dark shots, adds midtone contrast and boosts muted colours: gentle on skin,
punchier for landscapes and food, slightly warmer for food. Photos go to `Pictures/SmartCam`.

**Auto-shot** (toggle at top right) takes the picture by itself once the shot has been framed,
level and steady for 0.7 s.

## Build and install

Needs Android 10 or newer with a rear camera.

```
./gradlew testDebugUnitTest   # composition, exposure, tracking, enhancement tests
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
adb install app/build/outputs/apk/release/app-release.apk
```

## Notes

- Only tested with unit tests and builds so far; it hasn't been run on a phone yet, so expect
  some tuning of thresholds once it has.
- Manual shutter needs a camera that reports the MANUAL_SENSOR capability (most recent phones).
  Without it, only the frame-rate limit is used for motion.
- Rear camera only. The photo uses the camera's standard processing, not the phone maker's own
  camera app pipeline (Night mode, HDR+ and so on), and EXIF location isn't written.

## Code layout (`app/src/main/java/dev/smartcam/`)

- `SceneAnalyzer.kt`: runs the face, object and labelling models and builds a brightness grid per frame
- `Scene.kt`: scene classification from labels, faces and brightness
- `SubjectTracker.kt`: picks and follows the subject, measures its speed
- `Composition.kt`: where the subject should go and what to tell you
- `ExposureAdvisor.kt`: brightness targets, exposure compensation, shutter and frame rate choices
- `LumaGrid.kt`: frame brightness, subject brightness, clipping and horizon finding
- `PhotoEnhancer.kt` + `PhotoSaver.kt`: post-capture edit and saving
- `GuideOverlay.kt`: everything drawn over the preview
- `DeviceMotion.kt`: orientation, level and shake from the sensors
- `MainActivity.kt`: CameraX setup and the per-frame loop tying it together
