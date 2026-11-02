# PinPulse — Live Friends & Group Location Radar 📍

<p align="center">
  <b>Real-Time GPS Tracking, Circle & Friend Radar with Google Maps Navigation</b>
</p>

---

## 🚀 Features

- **Google Maps Integration:**
  - View live friends with Google Maps Streets, Satellite, and Hybrid tile layers (Zero API key setup needed!).
  - **"Go To Person" Button:** Launches the native Google Maps app on your phone with turn-by-turn driving directions directly to their exact live coordinates.
- **Custom Map Pins (Avatar + Name Only):**
  - Clean map pins showing circular avatar photo + name badge underneath.
  - Single-tap any person to see Distance, Movement Speed, Coordinates, and live status.
- **Avatar Customization & Group Leader Priority:**
  - Anyone can update their personal avatar with any photo from their phone.
  - Group Leaders can set custom avatars for members inside their group using photos from the Leader's phone (Leader choice overrides member avatar inside that group).
- **Two Ways to Connect:**
  - **Option A (Groups):** Instant join with a 6-character code. Group Leaders can kick members with **2-step confirmation and a 5-second countdown protection timer**.
  - **Option B (1-on-1 Friends):** Add by Friend Code or Google email. Mutual removal breaks link simultaneously on both sides.
- **Non-Stop Background Location & Instant Alert Broadcasts:**
  - Persistent Foreground Service keeps tracking 24/7 even when screen is locked or app is minimized.
  - If someone closes or pauses location sharing: they can still view others, but **all friends and group leaders immediately receive an alert notification**.
- **Smart Map Visibility:**
  - Filter between General Map, Friends Only, or any specific Group.
  - "Hide from General Map" toggle lets you hide someone from the main map while keeping them visible in their profile or group.
  - Solo tracking mode focuses on one person.
- **Anti-"App Not Installed" Update Guarantee:**
  - Fixed Application ID `com.pinpulse.radar`
  - Persistent RSA-2048 signing certificate committed in repository
  - Strictly incrementing version scheme (`0.1` -> `0.9` -> `1.0`)

---

## 📦 Download & Releases

Every push to this repository automatically builds and publishes a signed release APK to [GitHub Releases](https://github.com/DALI951/pinpulse/releases).

---

## 🛠️ Tech Stack

- **Platform:** Native Android (Kotlin)
- **UI Framework:** Jetpack Compose & Material 3
- **Map Engine:** Google Maps Tile Layers + Leaflet WebKit Bridge
- **Real-Time Sync:** Worldwide MQTT pub/sub over TCP/WebSockets (Zero server setup required)
- **Location Services:** Google Play Services Fused Location Provider Client
- **CI/CD:** GitHub Actions Automated Release Pipeline
