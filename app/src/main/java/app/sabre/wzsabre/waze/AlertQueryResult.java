package app.sabre.wzsabre.waze;

import java.util.List;

/**
 * The result of one Waze RT area query: the alerts the server added (as
 * AddAlertAction elements) and the uuids it removed (as "RmAlert," old_command
 * lines). Mirrors wzsabre 2.2 wazemo.AlertQueryResult.
 *
 * The RT /command endpoint is session-stateful, it only sends an alert once per
 * session, then a removal when it clears, so callers must MERGE these deltas into
 * a persistent cache rather than treat each result as a full snapshot. See
 * {@link WazeAlertCache}.
 */
final class AlertQueryResult {
    final List<WazeAlert> newAlerts;
    final List<String> removedIds;

    AlertQueryResult(List<WazeAlert> newAlerts, List<String> removedIds) {
        this.newAlerts = newAlerts;
        this.removedIds = removedIds;
    }
}
