package app.sabre.wzsabre.waze;

/** Outcome of a Waze report submission. */
final class ReportResult {
    final boolean accepted;
    final String uuid;
    final int points;
    final String error;
    private ReportResult(boolean a, String u, int p, String e) {
        accepted = a; uuid = u; points = p; error = e;
    }
    static ReportResult ok(String uuid, int points) { return new ReportResult(true, uuid, points, null); }
    static ReportResult fail(String error) { return new ReportResult(false, null, -1, error); }
}
