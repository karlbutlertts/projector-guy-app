#!/system/bin/sh
# A5-only root helper. Reads touch events without grabbing the input device.
DIR=/data/local/tmp/pg-touch-overlay
PKG=com.newlink.floatwindow
PREF_FLAG="@PREF_FLAG@"
[ "$(id -u)" = 0 ] || exit 1
case "$(getprop ro.product.device)" in pioneer5*) ;; *) exit 1;; esac
if [ -d "$DIR/guard" ]; then
 old=$(cat "$DIR/guard/pid" 2>/dev/null)
 case "$old" in
  ''|*[!0-9]*) exit 1;;
  *) if kill -0 "$old" 2>/dev/null && tr "\000" " " < "/proc/$old/cmdline" | grep -qF "$DIR/agent.sh"; then exit 0; fi;;
 esac
 rm -f "$DIR/guard/pid"; rmdir "$DIR/guard" 2>/dev/null
fi
mkdir "$DIR/guard" || exit 1
printf '%s\n' "$$" >"$DIR/guard/pid"
original=$(cat "$DIR/original-mode")
case "$original" in default|allow|ignore|deny|foreground) ;; *) exit 1;; esac
reader=
cleanup() {
 trap - EXIT HUP INT TERM
 [ -n "$reader" ] && kill "$reader" 2>/dev/null
 [ -n "$reader" ] && wait "$reader" 2>/dev/null
 cmd appops set "$PKG" SYSTEM_ALERT_WINDOW "$original"
 rm -f "$DIR/running" "$DIR/events" "$DIR/enabled" "$DIR/guard/pid"
 rmdir "$DIR/guard" 2>/dev/null
}
trap cleanup EXIT HUP INT TERM
input=
for name in /sys/class/input/event*/device/name; do
 if [ "$(cat "$name")" = fts_ts ]; then
  event=${name%/device/name}; input=/dev/input/${event##*/}; break
 fi
done
[ -n "$input" ] || exit 1
rm -f "$DIR/events"
mkfifo -m 600 "$DIR/events" || exit 1
getevent -lt "$input" >"$DIR/events" 2>"$DIR/input-error" &
reader=$!
exec 3<"$DIR/events"
cmd appops set "$PKG" SYSTEM_ALERT_WINDOW ignore || exit 1
hidden=1
last=0
printf '%s\n' "$$" >"$DIR/running"
while [ -f "$DIR/enabled" ] && [ -f "$PREF_FLAG" ]; do
 kill -0 "$reader" 2>/dev/null || exit 1
 if IFS= read -r -t 0.25 line <&3; then
  case "$line" in
   *EV_ABS*|*BTN_TOUCH*)
    read uptime rest </proc/uptime
    last=${uptime%.*}${uptime#*.}
    if [ "$hidden" = 1 ]; then
     cmd appops set "$PKG" SYSTEM_ALERT_WINDOW allow || exit 1
     hidden=0
    fi
    ;;
  esac
 fi
 if [ "$hidden" = 0 ]; then
  read uptime rest </proc/uptime
  now=${uptime%.*}${uptime#*.}
  if [ $((now-last)) -ge 500 ]; then
   cmd appops set "$PKG" SYSTEM_ALERT_WINDOW ignore || exit 1
   hidden=1
  fi
 fi
done
