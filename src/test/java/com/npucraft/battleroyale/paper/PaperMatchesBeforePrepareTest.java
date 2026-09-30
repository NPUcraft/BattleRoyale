package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.TestSupport;
import com.npucraft.battleroyale.session.GameSession;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperMatchesBeforePrepareTest {
    @Test void queueControlDecodeFailureAbortsBeforeZoneSelectionOrAnySnapshotCapture(){
        var matches=new PaperMatches(null,null,null,null,new Random(1),null,null,null,null,null,null,null,null,null,null,null);
        UUID player=UUID.randomUUID();var session=GameSession.waiting(UUID.randomUUID(),TestSupport.room("solo",1,8),Instant.now());
        session.join(player);session.prepare(TestSupport.map(Path.of("template")));
        var brokenControl=new IllegalStateException("corrupt queue control");
        matches.beforePrepare(ids->{assertEquals(List.of(player),List.copyOf(ids));throw brokenControl;});
        assertSame(brokenControl,assertThrows(IllegalStateException.class,()->matches.prepareDurably(session)));
        assertTrue(session.initialZone().isEmpty());assertTrue(matches.allEntries().isEmpty());
    }
}
