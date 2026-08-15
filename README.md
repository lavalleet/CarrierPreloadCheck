# Carrier Preload Check

Watches a Galaxy S20 FE (SM-G781U, Android 13) for carrier and OEM preload
packages coming back after they were removed.

The device is on Pure Talk, an MVNO riding AT&T's network (MCC/MNC 310/410), so
the AT&T account apps have nothing to authenticate against and Digital Turbine
Ignite has no business pushing sponsored apps to it. On 2026-08-14 it pushed
five anyway — Temu and four games — in a three-minute burst at 23:03.

## Two checks, answering different questions

**Named watch** — five packages with a per-package expectation:

| Package | Expected | Why not simply removed |
|---|---|---|
| `com.dti.att` | uninstalled | DT Ignite, the pusher itself |
| `com.facebook.system` | uninstalled | Facebook preload installer |
| `com.facebook.appmanager` | uninstalled | Facebook preload updater |
| `com.samsung.android.game.gos` | disabled | reinstalls itself seconds after `pm uninstall` |
| `com.samsung.klmsagent` | stopped | Knox-protected; uninstall, disable and suspend all refused |

**Baseline diff** — a snapshot of every installed package versus what is
installed now. This is the check that would have caught the overnight push,
since a named list cannot contain packages nobody has seen yet. New packages
are shown with install time and installer, and the baseline only moves when
the user explicitly accepts it.

## Layout

    AndroidManifest.xml               QUERY_ALL_PACKAGES + REQUEST_DELETE_PACKAGES
    src/.../MainActivity.java         the whole app; UI built in code, no layout XML
    res/                              adaptive launcher icon
    build.sh                          Gradle-free build: aapt2 -> javac -> d8 -> apksigner
    scripts/carrier-bloat-check.sh    desktop equivalent, needs the phone on USB

`~/bin/carrier-bloat-check.sh` is a symlink to the script here.

## Build

    ./build.sh            # build only
    ./build.sh install    # build, then adb install

No Gradle and no network: it uses the SDK build-tools directly. Requires
build-tools 35.0.0 and platform android-34.

## Platform behaviour worth remembering

Several things here were established by testing on the device, not assumed:

- **Permission revocation does not survive a reboot.** `pm revoke` on
  `WRITE_SECURE_SETTINGS` works, but install-time permissions are re-resolved
  from the manifest on every boot scan, so the grant returns. Disabling and
  uninstalling *do* survive. Do not bother re-revoking.
- **`pm uninstall --user 0` on a system app is the real ceiling without root.**
  The APK stays on the system partition; the package is gone for the user. It
  still appears in `pm list packages -u`. Restore with
  `cmd package install-existing`.
- **`game.gos` reports `Success` on uninstall and then reinstalls itself** — its
  `firstInstallTime` jumps to the moment after the uninstall. Only disabling
  sticks.
- **`klmsagent` cannot be touched**: `pm uninstall` and `pm disable-user` both
  fail, `pm suspend` returns `cmd restricted`. Force-stop works and does not
  survive a reboot, so the app reports it as information and deliberately does
  not let it drive the banner. An always-red indicator gets ignored.
- **Package counts are unstable for about a minute after boot** while the
  package manager finishes scanning (observed 491 settling to 493). Re-check
  before believing a diff taken immediately after a reboot.
- The app reads `ApplicationInfo.FLAG_STOPPED` (`0x00200000`), which is `@hide`
  in the SDK. This is bit math on the public `flags` field rather than
  reflection, so non-SDK interface restrictions do not apply. Verified to
  discriminate: a running package reads false.

## Removing a package from the app

Each new package in the diff gets its own button acting on that package alone —
no batch action and no iteration. An ordinary app is handed to the system
uninstaller; a system app opens its App info screen, where Disable is
available. The app cannot uninstall anything itself. For a carrier preload that
returns after an OTA, Disable is the strongest action available on-device, and
a real `pm uninstall --user 0` still needs adb.

## Adding a package to the watch list

Edit **both** the `WATCHED` array in `MainActivity.java` and the `<queries>`
block in the manifest. Miss the manifest and the new entry silently reads as
"not present" — a false green.
