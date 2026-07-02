package app.sabre.wzsabre;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests the version-comparison logic behind the update check. */
public class UpdateCheckerTest {

    @Test public void stripsLeadingV() {
        assertEquals("1.6", UpdateChecker.stripV("v1.6"));
        assertEquals("1.6", UpdateChecker.stripV("1.6"));
    }

    @Test public void newerMajorMinorPatch() {
        assertTrue(UpdateChecker.isNewer("1.6", "1.5.1"));
        assertTrue(UpdateChecker.isNewer("1.5.2", "1.5.1"));
        assertTrue(UpdateChecker.isNewer("2.0", "1.9.9"));
        assertTrue(UpdateChecker.isNewer("v1.6", "1.5"));   // handles a raw tag
    }

    @Test public void notNewerWhenEqualOrOlder() {
        assertFalse(UpdateChecker.isNewer("1.5.1", "1.5.1"));
        assertFalse(UpdateChecker.isNewer("1.5", "1.5.1"));
        assertFalse(UpdateChecker.isNewer("1.4", "1.5"));
        assertFalse(UpdateChecker.isNewer(null, "1.5"));
    }

    @Test public void toleratesSuffixes() {
        assertTrue(UpdateChecker.isNewer("1.6-beta", "1.5"));
        assertFalse(UpdateChecker.isNewer("1.5-rc1", "1.5"));
    }
}
