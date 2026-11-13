#!/usr/bin/env python3
"""Check the built APK does not accidentally include the vendored providers.

This is a packaging regression check, not a claim of runtime network isolation.
Run after assembleJpodDebug. No third-party Python dependencies are needed.
"""
import sys
import zipfile


def verify(path):
    forbidden = (b"Lcom/music/spotui/", b"Lcom/metrolist/spotify/",
                 b"Lcom/metrolist/innertube/", b"api.spotify.com",
                 b"accounts.spotify.com", b"open.spotify.com")
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        dex = [name for name in names if name.endswith(".dex")]
        if not dex:
            raise ValueError("APK contains no DEX files")
        for name in dex:
            data = apk.read(name)
            for needle in forbidden:
                if needle in data:
                    raise ValueError(f"Excluded provider found in {name}: {needle.decode()}")
        if any("spotify_mix" in name for name in names):
            raise ValueError("Upstream extracted fonts found in the APK")
    print(f"Provider isolation check passed: {len(dex)} DEX files inspected")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify_jpod_apk.py path/to/app-jpod-debug.apk")
    verify(sys.argv[1])
