# PinPulse — Live Friends & Circle Location Radar (`PLAN.md`)

> **Project Directory:** `F:\projects\pinpulse`
> **GitHub Repository:** `https://github.com/DALI951/pinpulse`
> **Constant Android Package ID (`applicationId`):** `com.pinpulse.radar`
> **Persistent Signing Keystore:** `app/keystore/pinpulse.jks` (alias: `pinpulse_key`)
> **Versioning Scheme:** `0.1` → `0.2` → ... → `0.9` → `1.0` → `1.1` ... (Monotonically increasing `versionCode`)

---

## 1. Core Identity, Updates & Anti-"App Not Installed" Protection

To guarantee you can always install new APK updates directly on top of an already-installed version without ever getting Android's **"App not installed / Package conflicts with an existing package"** error:

1. **Constant Application ID:**
   - Fixed permanently to `com.pinpulse.radar` across all builds (`debug` and `release`).
2. **Fixed Signing Certificate (`app/keystore/pinpulse.jks`):**
   - Committed to the repository so both local builds and GitHub Actions cloud builds sign every APK with the **exact same RSA-2048 certificate** (V1 + V2 signing enabled).
3. **Strict Versioning Progression (`0.x` → `0.9` → `1.0`):**
   - Release #1 = `v0.1`, Release #2 = `v0.2`, ..., Release #9 = `v0.9`, Release #10 = `v1.0`, Release #11 = `v1.1`, etc.
   - Formula: `MAJOR = N / 10`, `MINOR = N % 10` → `v${MAJOR}.${MINOR}`.
   - `versionCode` strictly increments (`100 + GITHUB_RUN_NUMBER`) so Android always recognizes every new build as a valid upgrade.

---

## 2. Authentication & Zero-Setup Accessibility

- **Sign In with Google / 1-Tap Google Account Entry:**
  - Users can sign in with their Google/Gmail identity in one step with **zero manual server/console configuration required**.
  - Also supports plugging in a Firebase `google-services.json` at any time if native Google Play Services account picker is desired.
- **Persistent User Profile:**
  - Automatically generates a unique **Friend Code** (e.g., `PULSE-4829`) and links it to the user's Google email and display name.
  - Session stays logged in permanently until the user explicitly signs out.

---

## 3. Map & Pin Mechanics (Google Maps)

- **Google Maps View Inside the App:**
  - Uses Google Maps layers (**Standard Streets**, **Satellite**, and **Hybrid** switcher).
- **Clean Map Pins (Avatar + Name Only):**
  - On the map itself, each person's marker displays **ONLY their circular Avatar photo and their Name label underneath**.
  - No clutter on the map canvas.
- **Single-Tap Avatar Action Sheet:**
  - Tapping **once** on any person's map pin opens their detailed bottom sheet showing:
    - Avatar, Name, Friend ID / Group Role
    - **Live Status Badge:** *"Live now"* (with green pulse) or *"Last seen Xm ago"* or *"Location Paused"*
    - **Distance from You:** e.g., `1.4 km away`
    - **Movement Speed:** e.g., `38 km/h` or `Stationary`
    - **Coordinates & Accuracy**
    - **"Go To (Open in Google Maps)" Button:** Launches the native Android **Google Maps** app (`google.navigation:q=LAT,LNG` / directions intent) with their exact live coordinates set as the destination for immediate turn-by-turn navigation.

---

## 4. Custom Avatar Mechanics (Self vs. Group Leader Priority)

1. **Personal Avatar (From Phone Gallery):**
   - Any user can pick a photo from their phone gallery (`PickVisualMedia` / image picker) to set or change their personal avatar at any time.
   - Images are automatically cropped to a clean circle, compressed for fast realtime sync, and broadcast to friends/groups.
2. **Group Leader Custom Avatar Override:**
   - Inside a Group, the **Group Creator (Leader/Admin)** can select any member in that group and choose **"Set Member Avatar in Group"** using a photo from the Leader's phone.
   - **Priority Rule:** While a user can still change their own personal avatar whenever they want, **inside that specific group, the avatar stays what the Group Leader chose** (unless the Group Leader changes or resets it).

---

## 5. Adding People: Both Option A (Groups) & Option B (1-on-1 Friends)

### Option A: Groups / Circles
- **Create Group:** Any user can create a group (becomes the **Group Leader**) and receives a shareable 6-character Group Code.
- **Instant Join:** Entering a Group Code joins the group **immediately** (no approval wait).
- **Group Leader Kick with 2-Step + 5-Second Countdown Protection:**
  - The Group Leader can kick any member from the group.
  - **Step 1 Prompt:** *"Are you sure you want to kick [Name] from [Group]?"* (`Continue` / `Cancel`).
  - **Step 2 Prompt (False-Removal Protection):** *"Final Confirmation: Kick [Name]?"* — The **`Yes, Kick`** button is **disabled and counts down for 5 seconds** (`Yes (5s)... 4s... 3s... 2s... 1s... Yes, Kick`) alongside an immediately clickable **`Cancel`** button.

### Option B: 1-on-1 Friends
- **Add Friend:** Search or enter a person's Friend Code or Gmail address to send a Friend Request.
- **Accept / Decline:** Pending requests appear with a notification badge. Location is shared once accepted.
- **Mutual Friend Removal:**
  - If either person clicks **"Remove Friend"**, the friendship is removed on **both sides simultaneously** — you immediately stop seeing them and they immediately stop seeing you.

---

## 6. Non-Stop 24/7 Background Location & Location-Off Alerts

1. **Non-Stop Background Location Service:**
   - Runs an Android **Foreground Location Service** (`FOREGROUND_SERVICE_LOCATION`) with a WakeLock and an **Ignore Battery Optimizations** prompt so Android does not kill tracking when the screen is off or the app is in the background.
   - Includes a Boot/Restart receiver so tracking resumes automatically.
   - If internet disconnects temporarily, the person's pin stays on the map at their **last known position** with a *"Last seen X ago"* indicator.
2. **Closing / Pausing Location Sharing & Instant Notifications:**
   - If a user toggles their own location sharing **OFF** (or revokes location permission):
     - **They can still view the map and see other people.**
     - **Instant Alert Broadcast:** An immediate notification (Android system notification + in-app alert banner) is sent to **all of their 1-on-1 Friends** AND **the Group Leader + all members of every Group they belong to**, stating:
       > *"⚠️ [Name] turned off their live location."*

---

## 7. Map Filtering, Solo Profile View & "Hide from General Map"

1. **Map View Switcher:**
   - Filter bar at the top of the map lets you switch between:
     - **General Map (All)**
     - **Friends Only (1-on-1)**
     - **Specific Group** (toggled from group chips or from a profile/group screen)
2. **Solo Person View:**
   - Open any person's profile and toggle **"Focus / Show Only This Person on Map"** to track solely that individual.
3. **"Hide from General Map" Toggle:**
   - Tap any person on the General Map and toggle **"Hide from General Map"**.
   - That person's pin is hidden from the main General Map view to reduce clutter, **but you can still see their live location anytime by going to their Profile** (or viewing their specific Group, or toggling *"Show on General Map"* back on).

---

## 8. GitHub Actions CI/CD & Automatic Releases

- Every push to `main` (when app files are updated) or manual workflow dispatch automatically:
  1. Sets up JDK 17 & Gradle on GitHub Actions (`ubuntu-latest`).
  2. Calculates the next version in the `0.1` → `0.9` → `1.0` sequence.
  3. Compiles and signs the release APK (`PinPulse-vX.Y.apk`) using `app/keystore/pinpulse.jks`.
  4. Publishes a GitHub Release under **Releases** on `DALI951/pinpulse` with the downloadable `.apk` attached.
