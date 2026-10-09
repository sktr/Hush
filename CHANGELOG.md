# Changelog

Standalone Bluetooth autoplay blocker. English UI by default, Japanese on Japanese devices.

## Unreleased

- English UI strings by default with Japanese in `values-ja`; non-Japanese locales fall back to English.
- Triple-layer block: A2DP trigger, transient audio focus steal, `MEDIA_STOP` broadcast with `STOP`+`PAUSE` dispatch over a full ~15s window.
- Volume saved on connect, restored on stop/timeout/destroy; re-entry keeps the first saved volume.
- In-app persistent debug log with share and clear.
- Removed: equalizer, ask-on-connect, app launch, launch delay, What\'s-new, language switcher, About links, non-English/Japanese locales, Play/F-Droid publishing.
