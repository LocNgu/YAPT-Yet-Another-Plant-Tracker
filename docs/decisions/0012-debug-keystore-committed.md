# ADR-0012: Debug keystore committed to the repository

**Status**: accepted

**Date**: 2024-01-01

## Context

Android APKs must be signed to install on a device. Debug builds use a debug keystore. By default, Android Studio generates a debug keystore at `~/.android/debug.keystore` with a known, fixed key. This keystore is machine-local and not version-controlled.

The problem: installing a new debug APK over an existing one requires both to be signed with the same key. If two developers (or the same developer on two machines, or a local build vs. a CI build) use different debug keystores, the OS treats them as different apps and refuses the upgrade — requiring an uninstall first.

For YAPT, CI builds debug APKs on every push. Testing with CI-built APKs alongside locally-built APKs would require an uninstall on every switch if keystores differ.

Alternatives considered:
- **Machine-local keystore (default)**: simple, but CI builds and local builds can't be installed side-by-side without uninstalling.
- **Inject keystore via CI secret**: adds CI complexity and doesn't help local-to-CI interop.
- **Commit a shared debug keystore**: any machine cloning the repo uses the same key; local and CI builds are always interchangeable.

## Decision

A consistent debug keystore is committed to the repo at `app/debug.keystore`. The `build.gradle.kts` debug signing config points to this file. CI uses the same file via the repo checkout.

This applies to **debug builds only**. The release keystore is never committed; its credentials are injected via GitHub Actions secrets.

## Consequences

- Debug APKs built locally and by CI can be installed over each other without uninstalling first.
- The debug keystore is not a security asset — debug builds are not distributed and Android treats debug signing as inherently untrusted. Committing it poses no meaningful security risk.
- Anyone who clones the repo gets the same debug signing identity, which is the point.
- The release keystore and its credentials remain outside the repo and are handled exclusively through CI secrets.
