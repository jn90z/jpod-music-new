# J-Pod Music — Tempo + a Spotui-style interface

Android client for your own **Raspberry Pi + Navidrome + Tailscale** music library.
This branch keeps Tempo's working Subsonic/OpenSubsonic client, Media3 playback,
downloads and Android Auto integration, and starts a Spotui UI port on top.

**Status: integration preview, not a complete port of every Spotui screen.**
The new Compose home feed and navigation are implemented; library, search,
album/artist/playlist detail, player, lyrics, queue, downloads and settings still
use Tempo's functional screens with a shared J-Pod dark/green theme. The Explore
home tab preserves Tempo's richer discovery, history and playlist actions while
those screens are ported. See [the feature map](docs/SPOTUI-INTEGRATION.md).

## Build and install

Requires JDK 17 (or 21), Android SDK platform 35 and build tools 35.0.0.
In Android Studio, select the **jpodDebug** build variant. From the command line:

```sh
./gradlew :app:assembleJpodDebug
```

Install `app/build/outputs/apk/jpod/debug/app-jpod-debug.apk` on Android 7+.
The application ID is `com.jn90z.jpod`, so this installs alongside Tempo.
Accounts/downloads do not automatically migrate from Tempo's separate sandbox.
Debug builds are for testing; stable upgrades require a consistent signing key.
GitHub Actions builds and uploads a debug APK on pushes to this branch.

## Connect your J-Pod

1. Connect **Tailscale on your phone** to the same tailnet as the Raspberry Pi.
2. In J-Pod's server login, enter your Navidrome address, for example
   `http://YOUR_PI_TAILSCALE_IP:4533` (or your existing working MagicDNS/HTTPS URL).
3. Enter your **Navidrome** username and password, the same credentials used in
   Tempo. These are not your Raspberry Pi or Tailscale account credentials.
4. Browse, search and play. Use the home Downloads shortcut or bottom tab for
   offline music. Long-press an album or song for Tempo's existing actions.

Tailscale runs separately on the phone; the app does not configure or embed a VPN.
HTTP is supported for Navidrome inside your encrypted tailnet. No Pi changes,
router port forwarding, Spotify account or Spotify application keys are required.
Server instructions: [jn90z/j-pod](https://github.com/jn90z/j-pod).

## Source layout

- `app/src/main`: Tempo foundation and new Compose home (`ui/jpod`).
- `app/src/tempo/java`: reused playback service, Android Auto browsing and toolbar.
- `app/src/jpod/res`: J-Pod branding and Spotui-style navigation/theme overlays.
- `upstream/spotui`: full pinned upstream source snapshot for further UI porting.
  **It is not a Gradle module or a packaged part of J-Pod.** Its Spotify, YouTube,
  Deezer, Soulseek and other providers are not wired into this application.

The `tempo`, `play` and `notquitemy` variants remain available. Original Tempo
README: [docs/TEMPO-UPSTREAM.md](docs/TEMPO-UPSTREAM.md).

## Credits and licensing

[Tempo](https://github.com/CappielloAntonio/tempo) and
[Spotui](https://github.com/Spotui/Spotui) are GPL-3.0 projects; their license files
and notices are preserved. The port adapts Spotui's home layout, colors and
navigation glyphs, and uses Tempo's Inter fonts. Spotui's Spotify-extracted fonts
and Spotify logos remain only in the excluded upstream snapshot; they are not
copied into the built J-Pod app. See [provenance](docs/SPOTUI-INTEGRATION.md).
