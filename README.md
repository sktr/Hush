<div align="center">
  <img src="docs/app-icon.svg" alt="Hush app icon" width="144"/>

  <h1>Hush</h1>

  <p><strong>Stop Bluetooth autoplay, silently.</strong></p>
  <p>Pick your car or speaker. When it connects, the app mutes, stops the autoplay, then restores your volume.</p>
</div>

## Download

Get the latest test APK from the [releases page](https://github.com/sktr/Hush/releases). On most phones use the `arm64-v8a` APK.

## How it works

1. Select a Bluetooth device (or any device).
2. On connect the music stream is muted and transient audio focus is taken.
3. A stop command is sent while playback is detected (up to ~15 seconds for late autoplay).
4. The original volume is restored when playback stops or the window ends.

An in-app debug log (connection history, volume values) is available for troubleshooting.

## Notes

- No account, no ads, no analytics, no internet connection required.
- Japanese UI only. Personal project.
