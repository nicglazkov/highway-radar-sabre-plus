package app.sabre.wzsabre.waze;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * WazeSession.checkErrors classifies in-band (HTTP-200) errors so a zombie session
 * recovers instead of silently dropping all Waze data. Without it, a "please relogin"
 * arriving with a 200 status looked like success and the feed went quiet forever.
 */
public class WazeSessionErrorTest {

    private static WazeProto.Batch serverError(int code, String desc) {
        return WazeProto.Batch.newBuilder()
                .addElement(WazeProto.Element.newBuilder()
                        .setError(WazeProto.ServerError.newBuilder()
                                .setCode(code).setDescription(desc)))
                .build();
    }

    private static WazeProto.Batch loginError(WazeProto.LoginError.AuthErrorType t) {
        return WazeProto.Batch.newBuilder()
                .addElement(WazeProto.Element.newBuilder()
                        .setLoginError(WazeProto.LoginError.newBuilder().setErrorType(t)))
                .build();
    }

    @Test
    public void reloginDescription_isSessionExpired() {
        try {
            WazeSession.checkErrors(serverError(500, "Session invalid, please relogin"));
            fail("expected SessionExpiredException");
        } catch (Exception e) {
            assertTrue(e instanceof WazeExceptions.SessionExpiredException);
        }
    }

    @Test
    public void unknownUserId_isSessionExpired() {
        try {
            WazeSession.checkErrors(serverError(0, "unknown userid"));
            fail("expected SessionExpiredException");
        } catch (Exception e) {
            assertTrue(e instanceof WazeExceptions.SessionExpiredException);
        }
    }

    @Test
    public void clientErrorCode_isAccountRejected() {
        try {
            WazeSession.checkErrors(serverError(403, "forbidden"));
            fail("expected AccountRejectedException");
        } catch (Exception e) {
            assertTrue(e instanceof WazeExceptions.AccountRejectedException);
        }
    }

    @Test
    public void serverErrorCode_isOperationError() {
        try {
            WazeSession.checkErrors(serverError(500, "internal failure"));
            fail("expected WazeOperationException");
        } catch (Exception e) {
            assertTrue(e instanceof WazeExceptions.WazeOperationException);
        }
    }

    @Test
    public void loginInternalIssues_isTransient_notReject() {
        // A transient server issue must NOT nuke a good account (that would burn the
        // anonymous-account quota re-registering for nothing).
        try {
            WazeSession.checkErrors(loginError(WazeProto.LoginError.AuthErrorType.INTERNAL_ISSUES));
            fail("expected WazeOperationException");
        } catch (Exception e) {
            assertTrue("transient login error must not be AccountRejected",
                    e instanceof WazeExceptions.WazeOperationException);
        }
    }

    @Test
    public void loginWrongPassword_isAccountRejected() {
        try {
            WazeSession.checkErrors(loginError(WazeProto.LoginError.AuthErrorType.WRONG_USER_PASSWORD));
            fail("expected AccountRejectedException");
        } catch (Exception e) {
            assertTrue(e instanceof WazeExceptions.AccountRejectedException);
        }
    }

    @Test
    public void cleanBatch_doesNotThrow() throws Exception {
        WazeSession.checkErrors(WazeProto.Batch.newBuilder()
                .addElement(WazeProto.Element.newBuilder().setOldCommand("SomeCmd,x"))
                .build());
        // no exception = pass
    }
}
