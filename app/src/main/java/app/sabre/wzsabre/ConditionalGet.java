package app.sabre.wzsabre;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP cache validators for a single feed, so a background refresh can ask the server
 * "has this changed?" instead of re-downloading a body it already holds.
 *
 * <p>This matters most for the Caltrans district feeds: they are multi-megabyte (D7
 * measured at 15.7 MB on 2026-08-04), they are refreshed while the driver is moving,
 * and the CWWP server does not gzip them. It does send {@code ETag} and
 * {@code Last-Modified} and answers {@code If-None-Match} with a 0-byte 304, so an
 * unchanged feed costs nothing instead of megabytes of cellular data.
 *
 * <p>Validators are committed only after a body has been read <em>and</em> parsed, the
 * same ordering {@link CHPSource} uses: committing early would leave validators that
 * match content the cache never received, so the next refresh would 304 onto a cache
 * that was never updated.
 *
 * <p>Fields are volatile because a refresh thread writes them while another may read.
 */
final class ConditionalGet {

    private volatile String etag;
    private volatile String lastModified;

    /**
     * Headers for the next GET.
     *
     * @param haveCachedBody whether a parsed body is currently held. When false the
     *                       result is empty: a 304 would leave nothing to serve.
     */
    Map<String, String> requestHeaders(boolean haveCachedBody) {
        String e = etag, lm = lastModified;
        if (!haveCachedBody || (e == null && lm == null)) return Collections.emptyMap();
        Map<String, String> headers = new LinkedHashMap<>(4);
        if (e != null)  headers.put("If-None-Match", e);
        if (lm != null) headers.put("If-Modified-Since", lm);
        return headers;
    }

    /**
     * Adopt the validators from a response whose body was read and parsed successfully.
     * Nulls are stored as-is so validators never outlive the response that set them.
     */
    void commit(String etag, String lastModified) {
        this.etag = etag;
        this.lastModified = lastModified;
    }
}
