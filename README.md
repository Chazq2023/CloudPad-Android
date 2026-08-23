![CloudPad Logo](cloudpad-logo.png)

[![Discord](https://img.shields.io/badge/Discord-Join%20Server-5865F2?logo=discord&logoColor=white)](https://discord.gg/VJfRa9qjf)

# CloudPad

CloudPad streams your PlayStation games to Android — either straight from Sony's cloud catalog, or from your own console over Remote Play — with an interface built for touchscreens, handhelds, and TV-style devices.

## Table of Contents

* [Features](#features)
* [Unofficial fork notice](#unofficial-fork-notice)
* [About this fork](#about-this-fork)
* [Releases](#releases)
* [Documentation](#documentation)
* [Contributing](#contributing)
* [Work in progress / known limitations](#work-in-progress--known-limitations)
* [Legal and responsible use](#legal-and-responsible-use)
* [Licence](#licence)
* [Credits](#credits)
* [Screenshots](#screenshots)

# Features

### Streaming
* **Internet Play (Cloud Play)** — stream from Sony's game catalog or your own owned PS3/PS4/PS5 library, no console required
* **Remote Play** — stream directly from your own PlayStation console, locally or over the internet
* **Live in-stream settings** — change resolution, FPS, bitrate, and codec mid-stream from the Quick Settings panel, no need to disconnect
* **AMD FidelityFX CAS image sharpening and FSR 1 upscaling** — real-time contrast-adaptive sharpening or upscaling for a crisper stream image
* **Background streaming** — minimize the app (home button, app switch, or the Quick Settings Minimize button) and the stream keeps running in the background, resuming exactly where you left off when you return
* **Customizable Performance Overlay** — Full or Minimal live stats view showing real network ping/RTT, FPS, and bitrate, with drag-to-reposition and adjustable opacity
* Configurable video profiles per streaming mode (Remote Play, Game Library, Game Catalog)
* Selectable Cloud Play datacenter/locale in Settings

### Console management
* Automatic local console discovery, PSN-based discovery, and registration
* Accurate console status tiles — Ready, Asleep, Waking, Offline — reflecting the console's real state, not a guess
* Wake consoles from rest mode, or put them to sleep, directly from the app
* Manual console entries for consoles outside local discovery

### In-stream Quick Menu
* Trophy list, trophy details, and trophy comparison against friends — without leaving your stream
* Friends list and direct messaging mid-session
* On-the-fly controller remapping, with one-tap reset to defaults
* Resize, reposition, and adjust the opacity of on-screen touch controls
* Toggle motion controls, touch haptics, Picture-in-Picture, and image sharpening mid-session

### Social & Trophies
* Browse your PSN friends list and message friends
* Full trophy list per game with progress and rarity, including an achieved (X/Y) counter, sort by earned date, and filter by rarity — both in the Trophies screen and the in-stream Quick Menu
* Trophy comparison against friends
* Per-game playtime tracking — total time, last played, longest session

### Customisation
* Custom controller button remapping
* Custom on-screen touch control layout, sizing, and opacity
* Theme colours
* Touch-friendly, Android-focused UI for handhelds, tablets, and TV-style devices

### Settings & diagnostics
* Import/export settings as JSON
* Session logs and verbose logging for troubleshooting
* Registered console and account management

# Unofficial fork notice

CloudPad is an unofficial Android-focused fork of the original Pylux project.

I am not the original author or maintainer of Pylux. CloudPad exists to maintain, fix, and experiment with the Android version of the app separately from the official upstream project.

For the official upstream project, please visit:

https://github.com/ForWard-Technologies-LLC/Pylux

Please support the official release. This fork contains Android-specific fixes, UI changes, streaming improvements, and experimental features.

# About this fork

This fork focuses on the Android version of Pylux, including Android handhelds and Android TV-style devices.

Changes in this fork may include:

* Android-specific UI changes
* PSCloud and Remote Play fixes
* Streaming stability improvements
* Bitrate and performance-related changes
* Touch/control improvements
* Experimental features not present in the official upstream build

This fork should not be considered the official Pylux build.

# Releases

For the latest Android APK, see the Releases page:

[CloudPad Android Releases](https://github.com/Chazq2023/CloudPad-Android/releases)

Source code for each APK release is available from this repository. Release builds should be tied to a matching commit or tag so users can access the corresponding source code.

# Documentation

For general Pylux setup guides, configuration, and controller options, see the original project documentation:

https://forward-technologies-llc.github.io/Pylux/

Some behaviour in this fork may differ from the official upstream version.

# Contributing

This fork is maintained separately from the official Pylux project.

If you want to contribute to this Android fork, please open a pull request against this repository.

For contributions to the official upstream project, please use the original repository:

https://github.com/ForWard-Technologies-LLC/Pylux

# Work in progress / known limitations

Pylux and this Android-focused fork are open-source community projects and should be treated as works in progress. Bugs, crashes, missing features, compatibility issues, streaming issues, and unexpected behaviour may occur.

This fork is provided as-is, with no guarantee that every feature, device, game, account, subscription, or streaming scenario will work correctly.

Cloud streaming / Internet Play typically requires a valid PlayStation account and an active PlayStation Plus tier that includes cloud streaming, such as PlayStation Plus Premium in supported regions. Remote Play from your own console may have different requirements.

Pylux and this Android-focused fork do not guarantee that any specific game, purchased title, subscription title, or catalogue title will be available for cloud streaming. Game availability, streaming support, regional access, subscription requirements, and supported devices are controlled by the relevant platform holder and may change at any time.

Users should check official store listings, subscription catalogues, subscription requirements, and streaming availability before purchasing games or subscriptions. Any purchases made for use with this fork are made at the user’s own risk.

# Legal and responsible use

Pylux is intended for use with games and content you own or are licensed to use, on hardware you own, with a valid account or subscription.

This project does not circumvent copy protection and does not facilitate piracy.

This project is not endorsed, sponsored, approved, or certified by Sony Interactive Entertainment, PlayStation, or any console manufacturer.

PlayStation, PS4, PS5, and related names are trademarks of their respective owners. All trademarks belong to their respective owners.

# Licence
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue)](https://github.com/ForWard-Technologies-LLC/Pylux/blob/master/LICENSES/AGPL-3.0-only-OpenSSL.txt)

This project is distributed under the same licence as the original Pylux project.

Pylux is licensed under the AGPL-3.0 licence with the OpenSSL exception, as used by the upstream project. 

Modified source code for this fork is made available in this repository.

# Credits

Pylux was originally developed by ForWard Technologies LLC.

This fork is based on the original Pylux project:

https://github.com/ForWard-Technologies-LLC/Pylux

Pylux is built on top of:

* Chiaki: https://git.sr.ht/~thestr4ng3r/chiaki
* chiaki-ng: https://github.com/streetpea/chiaki-ng

Special thanks to the original Pylux developer, the Chiaki development team, and the chiaki-ng maintainers for their excellent foundational work.

# Screenshots

### Login
Sign in to your PlayStation Network account directly from the app to unlock cloud streaming and console discovery.

<img src="readme%20assets/Login/Login%20Main%20Menu.png" width="420"> <img src="readme%20assets/Login/Login%20Steps.png" width="420">

### Cloud Play Catalogs
Browse the PS3, PS4, and PS5 catalogs, including your own owned library, from one screen.

<table>
<tr>
<td align="center"><b>PS3 Catalog</b><br><img src="readme%20assets/PS3%20Catalog/PS3%20Catalog.jpg" width="400"></td>
<td align="center"><b>PS4 Catalog</b><br><img src="readme%20assets/PS4%20Catalog/PS4%20Catalog.jpg" width="400"></td>
</tr>
<tr>
<td align="center" colspan="2"><b>PS5 Library</b><br><img src="readme%20assets/PS5%20Library/PS5%20Library.jpg" width="400"></td>
</tr>
</table>

### Remote Play
Console tiles reflect an accurate, live state — Ready, Asleep, Waking, or Offline — instead of a stale guess.

<img src="readme%20assets/Remote%20Play/Remote%20Play.jpeg" width="600">

### Quick Settings Panel (In-Stream)
Live stream settings, performance stats, controller remapping, trophies, friends, and messaging — all without ever leaving your stream.

<table>
<tr>
<td align="center"><b>Bitrate & Resolution</b><br><img src="readme%20assets/Quick%20menu%20features/Quick%20Settings%20Stream%20bitrate%20resolution.png" width="380"></td>
<td align="center"><b>Performance Overlay</b><br><img src="readme%20assets/Quick%20menu%20features/Quick%20Settings%20Performance%20Overlay.png" width="380"></td>
</tr>
<tr>
<td align="center"><b>Controller Remap</b><br><img src="readme%20assets/Quick%20menu%20features/Quick%20Settings%20Remap%20Controller.jpg" width="380"></td>
<td align="center"><b>Trophy List</b><br><img src="readme%20assets/Quick%20menu%20features/Quick%20Settings%20Trophy%20List.jpg" width="380"></td>
</tr>
<tr>
<td align="center"><b>Trophy Detail</b><br><img src="readme%20assets/Quick%20menu%20features/Quick%20Settings%20Trophy%20Detail.jpg" width="380"></td>
<td align="center"><b>Trophy Comparison</b><br><img src="readme%20assets/Quick%20menu%20features/Quick%20Settings%20Trophy%20Comparison.jpeg" width="380"></td>
</tr>
<tr>
<td align="center"><b>Friends List</b><br><img src="readme%20assets/Quick%20menu%20features/Quick%20Settings%20Friends%20List.jpeg" width="380"></td>
<td align="center"><b>Friend Messaging</b><br><img src="readme%20assets/Quick%20menu%20features/Quick%20Settings%20Friends%20Messaging.jpeg" width="380"></td>
</tr>
</table>

### Trophies
Full trophy list per game, with progress, rarity, and unlock status.

<img src="readme%20assets/Trophies/Trophies.jpg" width="600">

### Trophy Comparison
Compare your trophy progress against a friend, game by game, from the main menu.

<img src="readme%20assets/Trophy%20Comparison/Trophy%20Comparison.png" width="600">

### Trophy Unlocked Notification
An in-stream toast pops up the moment you unlock a trophy, without interrupting gameplay.

<img src="readme%20assets/Trophy%20Unlocked%20Notification/Trophy%20Unlocked%20Notification.jpg" width="600">

### Friends List & Messaging
Browse your PSN friends list and message them directly from the main menu.

<table>
<tr>
<td align="center"><img src="readme%20assets/Friends%20List/Friends%20List.jpeg" width="400"></td>
<td align="center"><img src="readme%20assets/Messaging/Friend%20Messaging.jpeg" width="400"></td>
</tr>
</table>

### Controller Remapping
Customise button and stick mappings for your controller.

<img src="readme%20assets/Controller%20Remap/Controller%20Remap.png" width="600">

### Image Sharpening (CAS)
AMD FidelityFX Contrast Adaptive Sharpening applied in real time to the stream image.

<table>
<tr>
<td align="center"><b>Before</b><br><img src="readme%20assets/Image%20Sharpening/Image%20Sharpening%20Before.jpg" width="420"></td>
<td align="center"><b>After</b><br><img src="readme%20assets/Image%20Sharpening/Image%20Sharpening%20After.jpg" width="420"></td>
</tr>
</table>

### Playtime Tracking
Per-game playtime stats — total playtime, last played, and longest session.

<img src="readme%20assets/Playtime/Playtime.jpg" width="500">

### Theme Showcase
Pick an accent colour and the whole UI updates to match.

<table>
<tr>
<td align="center"><b>Neon Blue</b><br><img src="readme%20assets/Theme%20Showcase/Neon%20Blue%20Theme.jpg" width="300"></td>
<td align="center"><b>Neon Green</b><br><img src="readme%20assets/Theme%20Showcase/Neon%20Green%20Theme.jpg" width="300"></td>
<td align="center"><b>Neon Orange</b><br><img src="readme%20assets/Theme%20Showcase/Neon%20Orange%20Theme.jpg" width="300"></td>
</tr>
<tr>
<td align="center"><b>Neon Pink</b><br><img src="readme%20assets/Theme%20Showcase/Neon%20Pink%20Theme.jpg" width="300"></td>
<td align="center"><b>Neon Yellow</b><br><img src="readme%20assets/Theme%20Showcase/Neon%20Yellow%20Theme.jpg" width="300"></td>
</tr>
</table>

### Settings
Appearance, controls, Remote Play and Cloud Play video profiles, CAS sharpening, diagnostics, and more.

<table>
<tr>
<td><img src="readme%20assets/Settings/Settings%201.png" width="300"></td>
<td><img src="readme%20assets/Settings/Settings%202.png" width="300"></td>
<td><img src="readme%20assets/Settings/Settings%203.png" width="300"></td>
</tr>
<tr>
<td><img src="readme%20assets/Settings/Settings%204.png" width="300"></td>
<td><img src="readme%20assets/Settings/Settings%205.png" width="300"></td>
<td><img src="readme%20assets/Settings/Settings%206.png" width="300"></td>
</tr>
<tr>
<td><img src="readme%20assets/Settings/Settings%207.png" width="300"></td>
<td><img src="readme%20assets/Settings/Settings%208.png" width="300"></td>
</tr>
</table>
