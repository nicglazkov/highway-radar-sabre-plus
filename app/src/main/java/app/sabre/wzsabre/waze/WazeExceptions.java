package app.sabre.wzsabre.waze;

/** Waze RT-protocol error conditions. */
final class WazeExceptions {
    private WazeExceptions() {}

    /** The server rejected the account/session (HTTP 4xx, e.g. 403). The slot should be replaced. */
    static final class AccountRejectedException extends Exception {
        AccountRejectedException(String message) { super(message); }
    }

    /** The session is no longer valid ("relogin", "unknown userid", "secretkey missing"). Re-login. */
    static final class SessionExpiredException extends Exception {
        SessionExpiredException(String message) { super(message); }
    }

    /** A generic protocol/operation failure (HTTP 5xx, register failure, etc.). */
    static final class WazeOperationException extends Exception {
        WazeOperationException(String message) { super(message); }
    }
}
