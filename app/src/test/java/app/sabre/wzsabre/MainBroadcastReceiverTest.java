package app.sabre.wzsabre;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class MainBroadcastReceiverTest {
    @Test public void classifiesOfficialActions() {
        assertEquals("REPORT",  MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.REPORT"));
        assertEquals("CONFIRM", MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.CONFIRM"));
        assertEquals("DISCARD", MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.DISCARD"));
    }
    @Test public void classifiesLegacyNames() {
        assertEquals("REPORT",  MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.SUBMIT_REPORT"));
        assertEquals("CONFIRM", MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.CONFIRM_REPORT"));
        assertEquals("DISCARD", MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.DISCARD_REPORT"));
    }
    @Test public void ignoresFetchAndShutdownAndNull() {
        assertNull(MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.REQUEST"));
        assertNull(MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.FETCH_REQUEST"));
        assertNull(MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.SHUTDOWN"));
        assertNull(MainBroadcastReceiver.classifyReportAction(null));
    }
}
