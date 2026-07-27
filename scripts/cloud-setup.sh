#!/usr/bin/env bash
# YAPT — Claude Code cloud-session setup script (issue #419)
#
# Makes Android Gradle builds work in a cloud session:
#   1. Installs the Android SDK (cmdline-tools + platform-36 + build-tools + platform-tools)
#      — needs dl.google.com, which must be allowlisted in the environment's
#        Network access -> Custom -> Allowed domains.
#   2. Points Gradle at that SDK.
#   3. Seeds the Gradle wrapper's dist cache from the pre-installed Gradle so
#      `./gradlew` can start (the pinned wrapper version is fetched from a
#      GitHub release asset that the session's proxy blocks).
#
# Dependency artifacts (AGP, androidx) still resolve over the network from
# maven.google.com / Maven Central, which are reachable — so builds run online,
# not with --offline. Only the wrapper's own Gradle distribution is blocked.
#
# Idempotent: safe to re-run when the environment cache is rebuilt.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
# Keep these in sync with app/build.gradle.kts (compileSdk) and the AGP-required
# build-tools revision (AGP 9.3.1 -> build-tools;35.0.0).
SDK_PACKAGES=("platform-tools" "platforms;android-37" "build-tools;35.0.0")

echo "==> Android SDK -> $ANDROID_HOME"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  echo "    installing command-line tools"
  tmp="$(mktemp -d)"
  curl -fsSL --retry 3 -o "$tmp/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  unzip -q "$tmp/cmdline-tools.zip" -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
else
  echo "    command-line tools already present"
fi

echo "==> Accepting licenses and installing SDK packages"
yes 2>/dev/null | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" "${SDK_PACKAGES[@]}" >/dev/null

# Let this repo's Gradle build find the SDK even if ANDROID_HOME isn't exported
# into the session shell. (Belt-and-suspenders: prefer setting ANDROID_HOME as an
# environment variable in the environment config so it survives repo re-clones.)
if [ -f settings.gradle.kts ] || [ -f settings.gradle ] || [ -f build.gradle.kts ]; then
  if ! grep -qs '^sdk.dir=' local.properties 2>/dev/null; then
    echo "sdk.dir=$ANDROID_HOME" >> local.properties
    echo "==> wrote sdk.dir to local.properties"
  fi
fi

echo "==> Seeding Gradle wrapper dist so ./gradlew can start (its dist download is proxy-blocked)"
WRAPPER_PROPS="gradle/wrapper/gradle-wrapper.properties"
if [ -f "$WRAPPER_PROPS" ]; then
  WRAPPER_VER="$(sed -nE 's#.*gradle-([0-9.]+)-(bin|all)\.zip.*#\1#p' "$WRAPPER_PROPS" | head -1)"
  PRE_GRADLE="$(command -v gradle || true)"
  [ -n "$PRE_GRADLE" ] && PRE_GRADLE_HOME="$(dirname "$(dirname "$(readlink -f "$PRE_GRADLE")")")"

  if [ -n "$WRAPPER_VER" ] && [ -n "${PRE_GRADLE_HOME:-}" ]; then
    # Trigger the wrapper once so it creates the (hash-named) dist dir, then fail
    # fast on the blocked download — we fill that dir in ourselves.
    timeout 60 ./gradlew --version >/dev/null 2>&1 || true
    DIST_PARENT="$HOME/.gradle/wrapper/dists/gradle-${WRAPPER_VER}-bin"
    HASHDIR="$(ls -d "$DIST_PARENT"/*/ 2>/dev/null | head -1 || true)"
    if [ -n "$HASHDIR" ] && [ ! -e "${HASHDIR}gradle-${WRAPPER_VER}-bin.zip.ok" ]; then
      rm -f "${HASHDIR}"*.lck "${HASHDIR}"*.part
      rm -rf "${HASHDIR}gradle-${WRAPPER_VER}"
      cp -a "$PRE_GRADLE_HOME" "${HASHDIR}gradle-${WRAPPER_VER}"
      touch "${HASHDIR}gradle-${WRAPPER_VER}-bin.zip.ok"
      echo "    seeded ${HASHDIR}gradle-${WRAPPER_VER} from $PRE_GRADLE_HOME"
      echo "    NOTE: ./gradlew will report the pre-installed Gradle's version"
      echo "          (a patch off the pinned $WRAPPER_VER); CI still uses the pinned build."
    else
      echo "    wrapper dist already seeded or download succeeded"
    fi
  else
    echo "    skipped (no pinned wrapper version or no pre-installed gradle found)"
  fi
fi

echo "==> Verifying: ./gradlew compileDebugKotlin"
# Online build: dependency artifacts (AGP, androidx) resolve from
# maven.google.com / Maven Central. Do NOT use --offline here — on a cold
# dependency cache it blocks AGP resolution and fails.
./gradlew compileDebugKotlin -q >/dev/null && echo "OK — cloud build works"
