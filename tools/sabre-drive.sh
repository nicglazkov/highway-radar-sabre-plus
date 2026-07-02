#!/usr/bin/env bash
#
# sabre-drive.sh — one-command test-drive logger for the CHP + Waze SABRE plugin.
#
# Run it on your Mac with the Pixel plugged in via USB. It finds adb (even if it's
# only inside an old Android Studio install), checks the phone and the plugin build,
# offers to install the debug APK for full logging, then captures a timestamped log
# of everything the plugin does on your drive. Stop with Ctrl-C when you're back.
#
# Usage:   bash sabre-drive.sh
#
# macOS bash 3.2 compatible. Nothing here modifies your Mac.

PKG="app.sabre.wzsabre"
HR_PKG="com.highwayradar.app"

# ── pretty output ──────────────────────────────────────────────────────────────
if [ -t 1 ]; then
  B=$(tput bold 2>/dev/null); N=$(tput sgr0 2>/dev/null)
  G=$(tput setaf 2 2>/dev/null); Y=$(tput setaf 3 2>/dev/null); RED=$(tput setaf 1 2>/dev/null)
else B=""; N=""; G=""; Y=""; RED=""; fi
say()  { printf "%s\n" "$*"; }
step() { printf "\n${B}==> %s${N}\n" "$*"; }
ok()   { printf "${G}  OK${N} %s\n" "$*"; }
warn() { printf "${Y}  ! ${N}%s\n" "$*"; }
err()  { printf "${RED}  x ${N}%s\n" "$*"; }

say "${B}CHP + Waze SABRE — test-drive logger${N}"

# ── 1. locate adb ────────────────────────────────────────────────────────────────
step "Locating adb"
ADB=""
if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; fi
for p in \
    "${ANDROID_HOME:-}/platform-tools/adb" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
    "$HOME/Library/Android/sdk/platform-tools/adb" \
    "$HOME/Android/Sdk/platform-tools/adb" \
    "/opt/homebrew/bin/adb" "/usr/local/bin/adb"; do
  if [ -z "$ADB" ] && [ -x "$p" ]; then ADB="$p"; fi
done
if [ -z "$ADB" ]; then
  # last resort: search the Android Studio SDK folder
  found=$(find "$HOME/Library/Android" -type f -name adb 2>/dev/null | head -1)
  [ -n "$found" ] && ADB="$found"
fi
if [ -z "$ADB" ]; then
  err "adb was not found."
  say "Install it one of these ways, then re-run this script:"
  say "  • Homebrew:  ${B}brew install --cask android-platform-tools${N}"
  say "  • Or in Android Studio: Settings → Languages & Frameworks → Android SDK →"
  say "    SDK Tools tab → check 'Android SDK Platform-Tools' → Apply."
  exit 1
fi
ok "adb: $ADB"

# ── 2. wait for the phone ────────────────────────────────────────────────────────
step "Connecting to the Pixel"
"$ADB" start-server >/dev/null 2>&1
while :; do
  # count device lines (skip the header and any blank line)
  states=$("$ADB" devices | awk 'NR>1 && NF>=2 {print $2}')
  ndev=$(printf "%s\n" "$states" | grep -c "^device$")
  if [ "$ndev" -gt 1 ]; then
    err "More than one device/emulator is connected."
    say "Unplug the others (and close any running emulator), then re-run."
    exit 1
  elif [ "$ndev" -eq 1 ]; then
    ok "Phone connected and authorized."
    break
  elif printf "%s\n" "$states" | grep -q "unauthorized"; then
    warn "Phone shows as 'unauthorized'. On the Pixel, tap ${B}Allow${N} on the USB-debugging"
    warn "prompt (tick 'Always allow from this computer')."
  else
    warn "No phone detected. Plug the Pixel in via USB, and turn on"
    warn "Settings → System → Developer options → ${B}USB debugging${N}."
    warn "(Unlock Developer options: Settings → About phone → tap 'Build number' 7×.)"
  fi
  say "  …retrying in 3s (Ctrl-C to quit)"
  sleep 3
done

MODEL=$("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
REL=$("$ADB" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
[ -n "$MODEL" ] && ok "Device: $MODEL (Android $REL)"

# ── helpers for the plugin build ────────────────────────────────────────────────
find_debug_apk() {
  local d f
  for d in "$(cd "$(dirname "$0")" 2>/dev/null && pwd)" "$HOME/Downloads" "$PWD"; do
    [ -z "$d" ] && continue
    f=$(ls -t "$d"/*debug*.apk 2>/dev/null | head -1)
    [ -n "$f" ] && { printf "%s" "$f"; return 0; }
  done
  return 1
}

install_debug() {
  local apk ans errf
  apk=$(find_debug_apk)
  if [ -z "$apk" ]; then
    warn "Debug APK not found. Put 'CHP-Waze-SABRE-*-debug.apk' in ~/Downloads"
    warn "(or next to this script) and re-run, or install it by hand. Continuing anyway."
    return
  fi
  printf "  Install the debug build from:\n    ${B}%s${N}\n" "$apk"
  printf "  This replaces the current build — you'll re-grant permissions and\n"
  printf "  re-select the plugin in Highway Radar. Install now? [y/N] "
  read -r ans
  case "$ans" in
    y|Y|yes|YES)
      errf=$(mktemp 2>/dev/null || echo /tmp/sabre_install_err)
      if "$ADB" install -r -d "$apk" 2>"$errf"; then
        ok "Installed the debug build."
      elif grep -qiE 'INCOMPATIBLE|signatures do not match|UPDATE_INCOMPATIBLE' "$errf"; then
        warn "Signature differs from the installed build — removing the old one first"
        warn "(this clears the plugin's saved settings)."
        "$ADB" uninstall "$PKG" >/dev/null 2>&1
        if "$ADB" install "$apk"; then ok "Installed the debug build."; else err "Install failed:"; cat "$errf"; fi
      else
        err "Install failed:"; cat "$errf"
      fi
      rm -f "$errf" 2>/dev/null ;;
    *) say "  Skipping install." ;;
  esac
}

# ── 3. check the plugin ──────────────────────────────────────────────────────────
step "Checking the plugin build"
if "$ADB" shell pm path "$PKG" 2>/dev/null | grep -q package; then
  VER=$("$ADB" shell dumpsys package "$PKG" 2>/dev/null | awk -F= '/versionName=/{print $2; exit}' | tr -d '\r')
  FLAGS=$("$ADB" shell dumpsys package "$PKG" 2>/dev/null | grep -m1 -iE 'pkgFlags=|flags=\[')
  ok "Installed: $PKG ${VER:+v$VER}"
  if printf "%s" "$FLAGS" | grep -qi DEBUGGABLE; then
    ok "It's the ${B}debug${N} build — full alert-payload logging is on."
  else
    warn "This looks like the ${B}release${N} build (no DEBUGGABLE flag)."
    warn "The release build logs source counts + errors, but not the full alert"
    warn "payload per fetch. For the richest logs, install the debug APK:"
    install_debug
  fi
else
  warn "The plugin isn't installed."
  install_debug
fi

# ── 4. check Highway Radar ───────────────────────────────────────────────────────
step "Checking Highway Radar"
if "$ADB" shell pm path "$HR_PKG" 2>/dev/null | grep -q package; then
  ok "Highway Radar is installed."
else
  warn "Highway Radar ($HR_PKG) isn't installed — make sure it's on the phone and"
  warn "set to use the plugin (Highway Radar → Settings → SABRE)."
fi

# ── 5. smoke check: open the plugin once ─────────────────────────────────────────
step "Opening the plugin settings once (starts the service, checks it doesn't crash)"
"$ADB" logcat -c >/dev/null 2>&1
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3
if "$ADB" logcat -d -v brief AndroidRuntime:E "*:S" 2>/dev/null | grep -q "$PKG"; then
  err "The plugin looks like it crashed on launch — that's useful! It'll be in the log."
else
  ok "Settings screen opened without an obvious crash."
fi
say "  (Glance at the phone: the Diagnostics section shows each source's status.)"

# ── 6. capture ───────────────────────────────────────────────────────────────────
step "Ready to record your drive"
LOGDIR="$HOME/sabre-drive-logs"; mkdir -p "$LOGDIR"
LOGFILE="$LOGDIR/sabre-drive-$(date +%Y%m%d-%H%M%S).log"
"$ADB" logcat -G 16M >/dev/null 2>&1   # bigger buffer, survives a brief disconnect
"$ADB" logcat -c >/dev/null 2>&1

CAFF=""; command -v caffeinate >/dev/null 2>&1 && CAFF="caffeinate -s"

say ""
say "Log file: ${B}$LOGFILE${N}"
say ""
say "${B}WHAT TO DO:${N}"
say "  1. Leave the phone plugged in and this Terminal open for the whole drive."
say "  2. Open Highway Radar and start it (green play button)."
say "  3. As you drive, jot quick notes with times, e.g.:"
say "       '2:14pm — CHP accident on I-80 WB near Vacaville — HR did NOT show it'"
say "  4. When you're back, return here and press ${B}Ctrl-C${N} to stop."
say ""
printf "Press ${B}Enter${N} to start recording… "
read -r _

on_stop() {
  printf "\n"
  ok "Recording stopped."
  # grab the on-device crash file too (works on the debug build)
  "$ADB" shell run-as "$PKG" cat files/last_crash.txt > "$LOGDIR/last_crash.txt" 2>/dev/null
  [ -s "$LOGDIR/last_crash.txt" ] && say "  Crash detail saved: $LOGDIR/last_crash.txt" || rm -f "$LOGDIR/last_crash.txt" 2>/dev/null
  SIZE=$(ls -lh "$LOGFILE" 2>/dev/null | awk '{print $5}')
  say ""
  say "${B}Saved:${N} $LOGFILE ${SIZE:+($SIZE)}"
  say ""
  say "${B}NEXT:${N} copy that file to your Windows machine (cloud drive, USB stick, etc.)"
  say "and tell Claude the path — it'll review the whole drive per source."
  exit 0
}
trap on_stop INT

ok "Recording… drive safe. (Ctrl-C here when you're done.)"
say ""
$CAFF "$ADB" logcat -v threadtime \
  SABREService:V WazeRT:V CHPSource:V LcsSource:V WinterSource:V WildfireSource:V \
  FGSStarter:V SABREProxy:V SABREBoot:V SABREAltStartup:V ServiceStartWorker:V \
  AndroidRuntime:E ActivityManager:I "*:S" \
  | tee "$LOGFILE"

# If logcat exits on its own (e.g. phone unplugged), still finalize.
on_stop
