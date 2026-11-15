# Spotify Clone — Personal Android Music Streaming & Spring Boot Backend

A full-stack, personal-use music streaming solution featuring a Spotify-inspired dark UI Android client powered by **NewPipe Extractor** and **Media3 ExoPlayer**, coupled with a **Java Spring Boot** backend hosted on **Render** backed by **Neon PostgreSQL**.

---

## Repository Structure

```text
Music-app/
├── .github/
│   └── workflows/
│       └── build.yml               # Automated Android APK compilation & GitHub Release workflow
├── android-app/                    # Android application (Kotlin, Media3, Room, NewPipe Extractor)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/spotifyclone/app/
│   │   │   │   ├── api/            # Retrofit client for Render backend & GitHub Releases API
│   │   │   │   ├── data/local/     # Room database (Playlists, Local Tracks, Downloaded Tracks)
│   │   │   │   ├── extractor/      # NewPipe YouTube Music search & Just-In-Time URL extractor
│   │   │   │   ├── playback/       # Media3 ExoPlayer, MusicService, and DownloadHelper
│   │   │   │   ├── update/         # In-App self-updater (GitHub Releases + FileProvider)
│   │   │   │   └── ui/             # Spotify dark-themed UI (Home, Search, Library, Now Playing)
│   │   │   └── res/                # XML layouts, drawables, and themes
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   └── settings.gradle.kts
└── backend/                        # Java Spring Boot 3.2.x REST API
    ├── Dockerfile                  # Lightweight multi-stage Docker build for Render
    ├── pom.xml                     # Maven configuration (Security, JWT, JPA, PostgreSQL)
    └── src/main/java/com/spotifyclone/backend/
        ├── config/                 # Neon PostgreSQL DATABASE_URL parser & Spring Security CORS
        ├── controller/             # AuthController & PlaylistController
        ├── dto/                    # Request/Response DTOs
        ├── model/                  # User, Playlist, Track JPA Entities
        ├── repository/             # Spring Data JPA Repositories (isolated by user_id)
        ├── security/               # JWT token provider & authentication filter
        └── service/                # Auth & Playlist business logic
```

---

## 1. Push Backend to GitHub & Deploy to Render

### Step 1: Initialize Git and Push to GitHub
From the repository root (`e:\Spotify`):

```powershell
# Initialize git (if not already done)
git init
git add .
git commit -m "Initial commit: Spring Boot backend and Android app"

# Link to your remote GitHub repository
git remote add origin https://github.com/<YOUR_GITHUB_USERNAME>/Music-app.git
git branch -M main
git push -u origin main
```

### Step 2: Configure Render
1. Open your [Render Dashboard](https://dashboard.render.com/).
2. Select your Web Service **`music-app-5b9t`** (or create a new Web Service pointing to `Music-app`).
3. Under **Settings**:
   - **Environment**: `Docker`
   - **Root Directory**: `backend`
   - **Dockerfile Path**: `Dockerfile`
4. Under **Environment Variables**, verify:
   - `DATABASE_URL`: Your Neon PostgreSQL connection string:
     ```text
     postgresql://<user>:<password>@<ep-pooler>.us-east-2.aws.neon.tech/neondb?sslmode=require
     ```
     > **Note:** The backend's `DatabaseConfig.java` automatically handles both `postgres://` and `postgresql://` formats, parses the credentials, and formats them into a production JDBC connection.
   - `JWT_SECRET`: *(Optional)* Any secure 32+ byte string (e.g. `SpotifyCloneSuperSecretKeyThatIsAtLeast32BytesLong!`).
5. Render will detect the commit, trigger a Docker build using the multi-stage `Dockerfile`, and launch your service.
6. Verify deployment by opening:
   ```text
   https://music-app-5b9t.onrender.com/
   ```
   You should receive:
   ```json
   { "service": "Spotify Clone Backend", "status": "UP", "version": "1.0.0" }
   ```

---

## 2. Setting Up Android Keystore & GitHub Secrets

For GitHub Actions to automatically compile and sign the release APK, you need to store your Android Keystore as GitHub Secrets.

### Step 1: Generate a Keystore (if you don't have one)
Run this command in PowerShell or Bash:

```powershell
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias musicapp -storepass YourStrongPassword -keypass YourStrongPassword -dname "CN=MusicApp, OU=Dev, O=Personal, L=City, S=State, C=US"
```

### Step 2: Convert Keystore to Base64
#### On Windows (PowerShell):
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard
```
*(The Base64 string is now copied to your clipboard).*

#### On Linux / macOS:
```bash
base64 -w 0 release.jks | pbcopy   # macOS
base64 -w 0 release.jks | xclip -selection clipboard # Linux
```

### Step 3: Add Secrets in GitHub
1. In your GitHub repository (`Music-app`), navigate to **Settings** > **Secrets and variables** > **Actions**.
2. Click **New repository secret** and add the following 4 secrets:
   - `KEYSTORE_BASE64`: Paste the entire Base64 string from Step 2.
   - `KEY_ALIAS`: `musicapp` (or the alias you used above).
   - `KEYSTORE_PASSWORD`: `YourStrongPassword`.
   - `KEY_PASSWORD`: `YourStrongPassword`.

---

## 3. Triggering Android Builds & GitHub Releases

Whenever you need a new APK build (e.g. after updating NewPipe Extractor versions):

1. Update `versionCode` and `versionName` in `android-app/app/build.gradle.kts` (e.g., versionCode = 2, versionName = "1.0.1").
2. Commit and tag:
   ```bash
   git add .
   git commit -m "Bump version to v1.0.1 with updated NewPipe extractor"
   git tag v1.0.1
   git push origin main --tags
   ```
3. GitHub Actions (`.github/workflows/build.yml`) will:
   - Check out code and set up JDK 17.
   - Decode your keystore.
   - Compile a signed release APK (`Music-app-v1.0.1.apk`).
   - Create a GitHub Release under tag `v1.0.1` and attach the APK.

---

## 4. In-App Updater & NewPipe Recovery Mechanism

- **Just-In-Time URL Resolution**: Audio streams from YouTube Music expire after several hours. The app extracts fresh streaming URLs on-demand right before ExoPlayer plays a track.
- **Handling Parsing Breakages**: If YouTube changes their internal APIs, NewPipe throws a `FatalFailureException`. The app catches this, suppresses unexpected crashes, and switches the UI into a **Source Broken** state (amber top banner).
- **In-Place App Upgrade**:
  - The banner prompts the user: *"Audio stream failed (YouTube Music parser broken). Tap here to check for an app update."*
  - The app queries `https://api.github.com/repos/<username>/Music-app/releases/latest`.
  - When a newer `versionCode` is found, it downloads the APK to cache and invokes Android's system package installer using `FileProvider`.
  - The system performs an **in-place upgrade** preserving all local Room databases, offline downloaded songs, and user login tokens.

---

## 5. Offline Mode

- Tracks can be downloaded directly from the **Now Playing** screen or track context menu.
- Media files are cached locally and indexed into Room (`DownloadedTrackDao`).
- The **Downloads** page enables offline playback even with no internet connection, and allows creating offline-only playlists.
