package app.sabre.wzsabre;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * Validator bookkeeping for conditional GET.
 *
 * <p>The Caltrans district feeds are large (D7 measured at 15.7 MB on 2026-08-04) and
 * are re-fetched while a driver is moving, so re-downloading an unchanged feed is the
 * app's single biggest use of cellular data. The CWWP server sends ETag and
 * Last-Modified and answers If-None-Match with a 0-byte 304 (verified live), which
 * these rules turn into actual savings.
 */
public class ConditionalGetTest {

    @Test
    public void sendsNothingBeforeFirstResponse() {
        ConditionalGet cg = new ConditionalGet();
        assertTrue("no validators yet, so nothing to ask about",
                cg.requestHeaders(true).isEmpty());
    }

    @Test
    public void sendsNothingWithoutACachedBody() {
        // A 304 when we hold no parsed body would leave the source with nothing to
        // serve, so never ask conditionally until there is something to fall back on.
        ConditionalGet cg = new ConditionalGet();
        cg.commit("\"abc\"", "Tue, 04 Aug 2026 22:58:14 GMT");
        assertTrue(cg.requestHeaders(false).isEmpty());
    }

    @Test
    public void sendsBothValidatorsOnceCached() {
        ConditionalGet cg = new ConditionalGet();
        cg.commit("\"6a726e86-439d7b\"", "Tue, 04 Aug 2026 22:58:14 GMT");

        Map<String, String> headers = cg.requestHeaders(true);
        assertEquals("\"6a726e86-439d7b\"", headers.get("If-None-Match"));
        assertEquals("Tue, 04 Aug 2026 22:58:14 GMT", headers.get("If-Modified-Since"));
    }

    @Test
    public void sendsOnlyTheValidatorsTheServerProvided() {
        ConditionalGet cg = new ConditionalGet();
        cg.commit("\"only-etag\"", null);

        Map<String, String> headers = cg.requestHeaders(true);
        assertEquals("\"only-etag\"", headers.get("If-None-Match"));
        assertNull("must not invent a date the server never sent",
                headers.get("If-Modified-Since"));
    }

    @Test
    public void forgetsValidatorsWhenServerStopsSendingThem() {
        ConditionalGet cg = new ConditionalGet();
        cg.commit("\"abc\"", "Tue, 04 Aug 2026 22:58:14 GMT");
        cg.commit(null, null);   // server dropped both on a later response
        assertTrue("stale validators must not outlive the response that set them",
                cg.requestHeaders(true).isEmpty());
    }
}
