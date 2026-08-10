#!/usr/bin/env bash
# YAPT — Claude Code cloud-session setup script (issue #419)
#
# Makes Android Gradle builds work in a cloud session:
#   1. Installs the Android SDK (cmdline-tools + the compileSdk platform +
#      build-tools + platform-tools)
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
# Bootstrap build of the command-line tools. sdkmanager only sees packages that
# existed when it was built, so a stale pin silently hides new platforms (a 2023
# build can't find `platforms;android-37`, released June 2026 — #544). This pin
# therefore only bootstraps: it installs the SDK-managed `cmdline-tools;latest`,
# and that copy installs everything else. Bumping it is optional, not load-bearing.
CMDLINE_TOOLS_BUILD="15859902"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
# The platform package is derived from compileSdk below so it can't drift from the
# build; only build-tools is pinned, to the AGP-required revision (AGP 9.3.1 -> 35.0.0).
BUILD_TOOLS_VERSION="35.0.0"

echo "==> Android SDK -> $ANDROID_HOME"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"

COMPILE_SDK="$(sed -nE 's/^[[:space:]]*compileSdk[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' \
  app/build.gradle.kts 2>/dev/null | head -1)"
if [ -z "$COMPILE_SDK" ]; then
  echo "!!! could not read compileSdk from app/build.gradle.kts — run this from the repo root" >&2
  exit 1
fi
SDK_PACKAGES=("platform-tools" "platforms;android-${COMPILE_SDK}" "build-tools;${BUILD_TOOLS_VERSION}")
echo "    compileSdk $COMPILE_SDK -> platforms;android-${COMPILE_SDK}"

# Older revisions of this script unzipped the tools straight into cmdline-tools/latest,
# which leaves an unmanaged copy (no package.xml) that sdkmanager won't upgrade. Drop it
# so cached environments re-provision instead of reusing 2023 tools forever.
if [ -d "$ANDROID_HOME/cmdline-tools/latest" ] && [ ! -f "$ANDROID_HOME/cmdline-tools/latest/package.xml" ]; then
  echo "    removing unmanaged cmdline-tools/latest from an earlier setup run"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
fi

BOOTSTRAP_DIR="$ANDROID_HOME/cmdline-tools/bootstrap-$CMDLINE_TOOLS_BUILD"
if [ ! -x "$BOOTSTRAP_DIR/bin/sdkmanager" ]; then
  echo "    installing bootstrap command-line tools ($CMDLINE_TOOLS_BUILD)"
  tmp="$(mktemp -d)"
  curl -fsSL --retry 3 -o "$tmp/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$BOOTSTRAP_DIR" "$tmp/unpacked"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/unpacked"
  mv "$tmp/unpacked/cmdline-tools" "$BOOTSTRAP_DIR"
  rm -rf "$tmp"
else
  echo "    bootstrap command-line tools already present"
fi

SDKMANAGER="$BOOTSTRAP_DIR/bin/sdkmanager"
accept_licenses() { yes 2>/dev/null | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true; }

echo "==> Updating to the SDK-managed cmdline-tools;latest"
accept_licenses
if ! "$SDKMANAGER" "cmdline-tools;latest" >/dev/null 2>&1; then
  echo "    could not install cmdline-tools;latest — continuing with the bootstrap tools"
fi
if [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
fi

echo "==> Accepting licenses and installing SDK packages"
accept_licenses
sdk_log="$(mktemp)"
if ! "$SDKMANAGER" "${SDK_PACKAGES[@]}" >"$sdk_log" 2>&1; then
  cat "$sdk_log" >&2
  echo "!!! sdkmanager failed to install: ${SDK_PACKAGES[*]}" >&2
  echo "    'Failed to find package' means the tools are older than the package — check" >&2
  echo "    \"$SDKMANAGER --list\" and bump CMDLINE_TOOLS_BUILD in this script." >&2
  rm -f "$sdk_log"
  exit 1
fi
rm -f "$sdk_log"

if [ ! -d "$ANDROID_HOME/platforms/android-${COMPILE_SDK}" ]; then
  echo "!!! platforms/android-${COMPILE_SDK} missing after a successful sdkmanager run" >&2
  exit 1
fi

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
      # Seeding only works when the pre-installed Gradle is close enough to the pin.
      # AGP 9 needs Gradle 9, so seeding an older major just swaps "can't download the
      # wrapper dist" for a confusing "minimum supported Gradle version" build failure.
      PRE_GRADLE_VER="$("$PRE_GRADLE" --version 2>/dev/null | sed -nE 's/^Gradle[[:space:]]+([0-9.]+).*/\1/p' | head -1)"
      if [ "${PRE_GRADLE_VER%%.*}" != "${WRAPPER_VER%%.*}" ]; then
        echo "!!! pre-installed Gradle is ${PRE_GRADLE_VER:-unknown}, wrapper pins $WRAPPER_VER" >&2
        echo "    Seeding across major versions would break the build instead of fixing it." >&2
        echo "    Allowlist the wrapper's download hosts (services.gradle.org," >&2
        echo "    downloads.gradle.org, release-assets.githubusercontent.com) in the" >&2
        echo "    environment's network config, or use an image with Gradle ${WRAPPER_VER%%.*}.x." >&2
        exit 1
      fi
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
