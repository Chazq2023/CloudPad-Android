
![Pylux Logo](pylux-logo.png)

# Pylux

[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue)](https://github.com/ForWard-Technologies-LLC/Pylux/blob/master/LICENSES/AGPL-3.0-only-OpenSSL.txt)
[![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20iOS%20%7C%20macOS%20%7C%20Windows%20%7C%20Linux-brightgreen)](https://github.com/ForWard-Technologies-LLC/Pylux/releases)

**Pylux is a free, open-source PS4 and PS5 Remote Play client for Android, Android TV, iOS, macOS, Windows, Linux, and Steam Deck.** It focuses on app-store installs, Internet Play (streaming the game catalog or your owned games), automatic console discovery, and a touch-friendly mobile UI — all from one community-maintained codebase.

## Download

<a href="https://play.google.com/store/apps/details?id=com.pylux.stream"><img src="assets/google-play-badge.svg" height="50" alt="Get it on Google Play"></a>&nbsp;<a href="https://www.dropbox.com/scl/fi/wf9cr349acdwkih0syrva/pylux-windows-installer-latest.exe?rlkey=m2egtuj8z7f5se6405gg09wct&dl=1"><img src="assets/windows-installer-badge.svg" height="50" alt="Download for Windows"></a>

<a href="https://apps.apple.com/us/app/pylux-remote-play/id6761292658"><img src="assets/app-store-badge.svg" height="50" alt="Download on the App Store"></a>&nbsp;<a href="https://apps.apple.com/us/app/pylux-remote-play/id6761292658"><img src="assets/mac-app-store-badge.svg" height="50" alt="Download on the Mac App Store"></a>

<a href="https://flathub.org/apps/io.github.ForWard_Technologies_LLC.Pylux"><img src="assets/flathub-badge.svg" height="50" alt="Get it on Flathub"></a>&nbsp;<a href="https://www.dropbox.com/scl/fi/wi8bjilwiklv7fde0b4ea/pylux-latest.AppImage?rlkey=3xne4ltuiq54ogfmng4gq24mp&dl=1"><img src="assets/linux-appimage-badge.svg" height="50" alt="Download AppImage"></a>

For full release notes and all downloads see the [Releases page](https://github.com/ForWard-Technologies-LLC/Pylux/releases).

## Screenshots

<p align="center">
  <img src="assets/screenshots/android-phones/01.png" alt="Pylux screenshot 1" width="45%" />
  <img src="assets/screenshots/android-phones/02.png" alt="Pylux screenshot 2" width="45%" />
  <img src="assets/screenshots/android-phones/03.png" alt="Pylux screenshot 3" width="45%" />
  <img src="assets/screenshots/android-phones/04.png" alt="Pylux screenshot 4" width="45%" />
</p>

## Features

- **Internet Play** — stream games from the game catalog or your owned game library
- **Remote Play** — low-latency streaming of your PlayStation console to any supported device
- **Cross-platform** — Android, Android TV, iOS, iPadOS, macOS, Windows, Linux, Steam Deck
- **App-store installs** — available on Google Play, App Store, and Mac App Store
- **Automatic console discovery and registration**
- **Touch-friendly controls** — mobile-optimized UI for phones and tablets

## Documentation

Full setup guides, configuration, and controller options at **[forward-technologies-llc.github.io/Pylux](https://forward-technologies-llc.github.io/Pylux/)**.

## Contributing

Fork the repo, create a branch, and open a pull request targeting `release/beta`. When merged, CI automatically builds and deploys to all platforms (Google Play, TestFlight, App Store Connect, Dropbox).

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full workflow and local development setup.

## What needs work

- **iOS** needs the most work — cleanup, hardening, and refactors are all in scope.
- **Android** is more stable but still benefits from fixes and polish.
- **Desktop (Qt)** is where merge pain with [upstream Chiaki](https://github.com/streetpea/chiaki-ng) shows up most. Prefer adding new code in new files when practical and skip sweeping rewrites that block pulling upstream fixes.
- **Cherry-picks from upstream** are welcome. If you spot something upstream has that Pylux is missing, open an issue or try adding it yourself.

AI-assisted contributions are welcome — just follow good coding standards and fully test your changes before submitting.

## Why Pylux?

I told ChatGPT to name the project and promised I'd actually use the first thing it said. It said Pylux...

## Legal & responsible use

Pylux is intended for use with games and content you own or are licensed to use, on hardware you own, with a valid account or subscription. It does not circumvent copy protection or facilitate piracy.

This project is not endorsed or certified by the console manufacturer. All trademarks belong to their respective owners.

For questions about this project or responsible use, contact [forward.technologies.llc@gmail.com](mailto:forward.technologies.llc@gmail.com).

## Credits

Pylux is built on top of [Chiaki](https://git.sr.ht/~thestr4ng3r/chiaki) and [chiaki-ng](https://github.com/streetpea/chiaki-ng). Special thanks to the original Chiaki development team and the chiaki-ng maintainers for their excellent foundational work. Pylux extends that work with a focus on app-store distribution, mobile and Android TV support, and Internet Play.
