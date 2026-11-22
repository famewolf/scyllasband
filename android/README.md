# Scylla's Band TTS for Android

A system Text-to-Speech engine that makes the 14 Scylla's Band voices available
to any Android app, modelled on SherpaTTS. Local inference only; no account,
cloud service, telemetry, or billing.

## Build

Prerequisites: Android SDK (platform 37), NDK 30, CMake, JDK 21, Gradle.
The project carries a `local.properties` pointing at the SDK/NDK installed on
this box. If building elsewhere, edit those two paths.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
cd /home/famewolf/ha_config_repo/android-tts
./gradlew :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk` (about 73 MB). The 471 MB
voice model is **not** in the APK; it is downloaded on first launch.

If CMake reports a missing `cmake;3.22.1`, this project pins CMake 4.5.1 from
`/usr/bin/cmake` through a writable SDK overlay at `/home/famewolf/android-sdk-work`.

## Install and set up

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Open the app and tap **Download voice model (~470 MB)**. The bundle is
   verified per-file against the release's `SHA256SUMS.json` before install.
2. In the voice list, **Play sample** streams a studio recording from
   `lowkeytea.dev`; **Use** selects the voice for local synthesis.
3. **Speak locally** synthesizes your text on-device with the chosen voice.
4. Android Settings → Text-to-speech output → select **Scylla's Band TTS**.

The TTS engine works fully offline after the model is installed. Sample playback
needs a network connection.

## F-Droid notes

- All new source is Apache-2.0, matching the runtime and model. The upstream
  `scyllasband-android` module and `libscyllasband` are copied in verbatim with
  their Apache-2.0 headers. No GPL code from SherpaTTS was copied.
- Build from source requires the model only at runtime, so the F-Droid build
  needs no large binary in the source tree.
- No proprietary blobs, no network permission beyond the model download and
  optional sample streaming, no push services, no analytics.

## Layout

```
app/                  TTS service, model downloader, voice catalog, settings UI
scyllasband-android/  upstream Kotlin wrapper + JNI (Apache-2.0)
libscyllasband/       upstream C++ runtime + CMake (Apache-2.0)
```
