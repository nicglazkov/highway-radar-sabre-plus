package app.sabre.wzsabre.waze;

/** Authenticated session state returned by a successful login. */
final class WazeSessionInfo {
    final long   serverSessionId;
    final String secretKey;
    final String globalUserId;

    WazeSessionInfo(long serverSessionId, String secretKey, String globalUserId) {
        this.serverSessionId = serverSessionId;
        this.secretKey = secretKey;
        this.globalUserId = globalUserId;
    }
}
