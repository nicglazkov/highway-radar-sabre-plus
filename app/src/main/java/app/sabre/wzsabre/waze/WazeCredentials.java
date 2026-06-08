package app.sabre.wzsabre.waze;

/**
 * Anonymous Waze account credentials, minted by the /rtserver/distrib/static
 * register endpoint. {@code community} is the username, {@code secret} the password.
 */
final class WazeCredentials {
    final String community;
    final String secret;

    WazeCredentials(String community, String secret) {
        this.community = community;
        this.secret = secret;
    }
}
