package app.sabre.wzsabre.waze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Removals arrive as "RmAlert,&lt;uuid&gt;" old_command lines, not a protobuf message. */
public class WazeRtCodecTest {

    @Test
    public void parsesRmAlertRemovalsAndIgnoresOthers() {
        WazeProto.Batch batch = WazeProto.Batch.newBuilder()
                .addElement(WazeProto.Element.newBuilder().setOldCommand("RmAlert,uuid-1").build())
                .addElement(WazeProto.Element.newBuilder().setOldCommand("RmAlert, uuid-2 ").build())
                .addElement(WazeProto.Element.newBuilder().setOldCommand("SomeOtherCmd,x").build())
                .addElement(WazeProto.Element.newBuilder().build())   // no old_command
                .build();
        List<String> removed = WazeRtCodec.parseRemovedAlertIds(batch);
        assertEquals(Arrays.asList("uuid-1", "uuid-2"), removed);
    }

    @Test
    public void emptyBatchHasNoRemovals() {
        assertTrue(WazeRtCodec.parseRemovedAlertIds(
                WazeProto.Batch.newBuilder().build()).isEmpty());
    }

    // ── At / SeeMe command builders (Task R4) ──────────────────────────────

    @Test
    public void atCommandFormatsMatchedSegmentNodes() {
        assertEquals("At,-122.2712,37.8044,0,90,1,111,222,T,0,-1,-1,0",
                WazeRtCodec.atCommand(-122.2712, 37.8044, 90, 111, 222));
    }

    @Test
    public void atCommandUsesMinusOneForNoMatch() {
        assertEquals("At,-122.2712,37.8044,0,90,1,-1,-1,T,0,-1,-1,0",
                WazeRtCodec.atCommand(-122.2712, 37.8044, 90, -1, -1));
    }

    @Test
    public void seeMeCommandMode2FollowsReferenceFormula() {
        assertEquals("SeeMe,2,2,T,T,T,1,-1,1,7", WazeRtCodec.seeMeCommand(2));
    }

    @Test
    public void seeMeCommandMode1MatchesExistingConstant() {
        assertEquals("SeeMe,1,2,T,T,T,1,-1,1,7", WazeRtCodec.seeMeCommand(1));
    }

    @Test
    public void noArgSeeMeCommandStillWorks() {
        assertEquals("SeeMe,1,2,T,T,T,1,-1,1,7", WazeRtCodec.seeMeCommand());
    }
}
