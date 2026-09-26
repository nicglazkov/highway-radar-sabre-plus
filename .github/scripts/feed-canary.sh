#!/usr/bin/env bash
# Checks that each public upstream feed the plugin depends on is reachable and
# still has the shape the parser expects. Prints a Markdown report to the file
# named by REPORT (default: canary-report.md) and exits non-zero if any feed
# failed. Waze is deliberately not checked: reaching it means opening an
# anonymous session from a shared cloud address on a schedule, which is the
# kind of traffic that gets blocked.
set -u

REPORT="${REPORT:-canary-report.md}"
UA="SABRE Plus feed canary (+https://github.com/nicglazkov/highway-radar-sabre-plus)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

failures=0
rows=()

# check <name> <url> <grep-pattern that must match> <grep-pattern that must NOT match>
check() {
  local name="$1" url="$2" must="$3" must_not="${4:-}"
  local body="$TMP/$name" code="" size="" reason=""
  local attempt
  for attempt in 1 2; do
    code="$(curl -sS -m 90 -L -A "$UA" -o "$body" -w '%{http_code}' "$url" 2>"$TMP/$name.err" || echo "000")"
    size="$(wc -c < "$body" 2>/dev/null || echo 0)"
    reason=""
    if [ "$code" != "200" ]; then
      reason="HTTP $code"
    elif [ "$size" -lt 500 ]; then
      reason="body is only $size bytes"
    elif ! grep -q -E -- "$must" "$body"; then
      reason="expected pattern not found: $must"
    elif [ -n "$must_not" ] && grep -q -E -- "$must_not" "$body"; then
      reason="error pattern found: $must_not"
    fi
    [ -z "$reason" ] && break
    # One retry after a pause covers a transient blip or a shared-quota 429.
    [ "$attempt" = 1 ] && sleep 45
  done
  if [ -z "$reason" ]; then
    rows+=("| $name | OK | HTTP $code, $size bytes |")
    echo "OK   $name ($size bytes)"
  else
    rows+=("| $name | **FAIL** | $reason |")
    echo "FAIL $name: $reason"
    failures=$((failures + 1))
  fi
}

check "CHP incidents" \
  "https://media.chp.ca.gov/sa_xml/sa.xml" \
  "<Log[ >]"

check "Caltrans lane closures (District 3)" \
  "https://cwwp2.dot.ca.gov/data/d3/lcs/lcsStatusD03.xml" \
  "<lcs[ >]"

check "Caltrans chain controls (District 3)" \
  "https://cwwp2.dot.ca.gov/data/d3/cc/ccStatusD03.xml" \
  "<cc[ >]"

check "WFIGS wildfires" \
  "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Incident_Locations_Current/FeatureServer/0/query?where=POOState%3D%27US-CA%27%20AND%20IncidentTypeCategory%3D%27WF%27%20AND%20ActiveFireCandidate%3D1&outFields=IncidentName%2CIncidentSize%2CPercentContained%2CFireDiscoveryDateTime%2CUniqueFireIdentifier%2CIncidentTypeCategory&returnGeometry=true&outSR=4326&f=json" \
  '"features"' \
  '"error"'

{
  echo "Feed check on $(date -u +'%Y-%m-%d %H:%M UTC')."
  echo
  echo "| Feed | Status | Detail |"
  echo "|---|---|---|"
  printf '%s\n' "${rows[@]}"
  echo
  if [ "$failures" -gt 0 ]; then
    echo "A failing feed means the plugin is silently showing nothing from that source. Check whether the upstream changed its URL or format, then update the parser."
  else
    echo "All feeds are reachable and have the expected shape."
  fi
} > "$REPORT"

exit "$failures"
