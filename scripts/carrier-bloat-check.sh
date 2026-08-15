#!/bin/bash
#
# carrier-bloat-check.sh
#
# Verifies that carrier/preload packages removed from the S20 FE stay removed.
# Expected state for each package, per user 0:  installed=false  stopped=true
#
# An OTA or factory reset is the realistic way these come back. A reboot alone
# does not undo the uninstall, so drift reported here is worth looking at.
#
# Exit codes: 0 = all good, or no device attached (not an error at login)
#             1 = adb missing / unusable
#             2 = drift detected on one or more packages

ADB="${ADB:-/opt/local/bin/adb}"
LOG="${LOG:-$HOME/Library/Logs/carrier-bloat-check.log}"

# Packages to police.
#
# Deliberately limited to the three pushers rather than every package removed.
# A named list only catches these coming back; it cannot catch a preload that
# has not been seen before, which is the more likely event. Widening the list
# does not fix that -- it just makes the blind spot feel smaller.
#
# The other seven removed (com.att.dh, com.att.personalcloud, com.att.iqi,
# com.att.android.attsmartwifi, com.att.callprotect, com.att.csoiam.mobilekey,
# com.att.myWireless) are recorded here for reference only.
PACKAGES=(
  com.dti.att
  com.facebook.system
  com.facebook.appmanager
)

WAIT_SECONDS="${WAIT_SECONDS:-90}"   # how long to wait for the device at login
NOTIFY="${NOTIFY:-1}"                # 1 = macOS notification on drift

mkdir -p "$(dirname "$LOG")"

log() { printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >>"$LOG"; }

notify() {
  [ "$NOTIFY" = "1" ] || return 0
  /usr/bin/osascript -e "display notification \"$1\" with title \"Carrier preload check\"" \
    >/dev/null 2>&1
}

if [ ! -x "$ADB" ]; then
  log "ERROR adb not executable at $ADB"
  exit 1
fi

# Wait for an authorized device; the phone may not be plugged in at login.
serial=""
deadline=$(( $(date +%s) + WAIT_SECONDS ))
while :; do
  serial=$("$ADB" devices 2>/dev/null | tr -d '\r' | awk '$2=="device"{print $1; exit}')
  [ -n "$serial" ] && break
  [ "$(date +%s)" -ge "$deadline" ] && break
  sleep 5
done

if [ -z "$serial" ]; then
  # Distinguish "not plugged in" from "plugged in but not trusted".
  if "$ADB" devices 2>/dev/null | tr -d '\r' | grep -q 'unauthorized'; then
    log "device attached but UNAUTHORIZED -- accept the USB debugging prompt"
    notify "Device unauthorized; check skipped"
  else
    log "no device within ${WAIT_SECONDS}s -- skipped"
  fi
  exit 0
fi

drift=()
for pkg in "${PACKAGES[@]}"; do
  # First "User 0:" line is the resolvable record. Packages that were updated
  # system apps print a second line for the shadowed base copy; ignore it.
  state=$("$ADB" -s "$serial" shell dumpsys package "$pkg" 2>/dev/null \
            | tr -d '\r' | grep -E '^[[:space:]]+User 0: ' | head -1)

  if [ -z "$state" ]; then
    log "OK      $pkg -- absent from package db"
    continue
  fi

  installed=$(printf '%s' "$state" | grep -oE 'installed=[a-z]+' | cut -d= -f2)
  stopped=$(printf '%s' "$state" | grep -oE 'stopped=[a-z]+' | cut -d= -f2)

  if [ "$installed" = "false" ] && [ "$stopped" = "true" ]; then
    log "OK      $pkg -- installed=false stopped=true"
  else
    log "DRIFT   $pkg -- installed=$installed stopped=$stopped"
    drift+=("$pkg")
  fi
done

if [ ${#drift[@]} -gt 0 ]; then
  log "RESULT  drift on ${#drift[@]} package(s): ${drift[*]}"
  notify "Returned: ${drift[*]}"
  exit 2
fi

log "RESULT  all ${#PACKAGES[@]} packages still removed ($serial)"
exit 0
