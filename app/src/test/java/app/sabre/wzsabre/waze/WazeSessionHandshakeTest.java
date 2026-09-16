package app.sabre.wzsabre.waze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The SeeMe/SetMood/Location/MapDisplayed handshake runs once per login, like the
 * official client, and its response batch is handed back to the caller instead of
 * being discarded. The RT server sends each alert once per session, so a discarded
 * MapDisplayed response around the driver would swallow the nearest alerts.
 */
public class WazeSessionHandshakeTest {

    /** Canned server: login always succeeds, every command answers one RmAlert line. */
    private static final class FakeHttp extends WazeHttpClient {
        final List<String> urls = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();

        @Override
        HttpResult post(String url, byte[] body, Map<String, String> headers) {
            urls.add(url);
            bodies.add(new String(body, StandardCharsets.UTF_8));
            if (url.endsWith(WazeConstants.PATH_LOGIN)) {
                WazeProto.Batch login = WazeProto.Batch.newBuilder()
                        .addElement(WazeProto.Element.newBuilder()
                                .setLoginResponse(WazeProto.LoginResponse.newBuilder()
                                        .setLoginSuccess(WazeProto.LoginSuccess.newBuilder()
                                                .setServerSessionId(urls.size())
                                                .setSecretKey("secret")
                                                .setGlobalUserId("7"))))
                        .build();
                return new HttpResult(200, login.toByteArray());
            }
            WazeProto.Batch cmd = WazeProto.Batch.newBuilder()
                    .addElement(WazeProto.Element.newBuilder()
                            .setOldCommand("RmAlert,from-handshake-" + urls.size()))
                    .build();
            return new HttpResult(200, cmd.toByteArray());
        }

        int commandPosts() {
            int n = 0;
            for (String u : urls) if (u.endsWith(WazeConstants.PATH_COMMAND)) n++;
            return n;
        }

        int loginPosts() {
            int n = 0;
            for (String u : urls) if (u.endsWith(WazeConstants.PATH_LOGIN)) n++;
            return n;
        }
    }

    private static WazeSession session(FakeHttp http) {
        return new WazeSession("row", DeviceIdentity.random(),
                new WazeCredentials("community", "secret"), http);
    }

    @Test
    public void firstPrepareRunsHandshakeAndReturnsItsBatch() throws Exception {
        FakeHttp http = new FakeHttp();
        WazeSession s = session(http);

        WazeProto.Batch batch = s.prepareForArea(37.80, -122.27);

        assertNotNull("handshake batch must be returned, not discarded", batch);
        assertEquals(1, batch.getElementCount());
        assertTrue(batch.getElement(0).getOldCommand().startsWith("RmAlert,from-handshake-"));
        assertEquals(1, http.loginPosts());
        assertEquals(1, http.commandPosts());
        String handshake = http.bodies.get(http.bodies.size() - 1);
        assertTrue("handshake keeps the official MapDisplayed line", handshake.contains("MapDisplayed,"));
        assertTrue(handshake.startsWith("SeeMe,"));
    }

    @Test
    public void secondPrepareOnLiveSessionSkipsHandshake() throws Exception {
        FakeHttp http = new FakeHttp();
        WazeSession s = session(http);
        s.prepareForArea(37.80, -122.27);
        int postsAfterFirst = http.urls.size();

        WazeProto.Batch second = s.prepareForArea(37.81, -122.28);

        assertNull("no handshake on an already-handshaken session", second);
        assertEquals("no network traffic at all", postsAfterFirst, http.urls.size());
    }

    @Test
    public void handshakeRunsAgainAfterSessionInvalidated() throws Exception {
        FakeHttp http = new FakeHttp();
        WazeSession s = session(http);
        s.prepareForArea(37.80, -122.27);
        s.invalidateSession();

        WazeProto.Batch again = s.prepareForArea(37.80, -122.27);

        assertNotNull("fresh login gets a fresh handshake", again);
        assertEquals(2, http.loginPosts());
        assertEquals(2, http.commandPosts());
    }
}
