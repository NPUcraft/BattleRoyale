package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.session.GameState;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueueExitPayloadTest {
    @Test void savedEnvelopeRestoresOriginalBytesAfterRestartWithoutAnInMemoryMap(){
        UUID owner=UUID.randomUUID(),session=UUID.randomUUID();byte[] item=new byte[5000];new Random(17).nextBytes(item);
        var saved=new QueueExitPayload(owner,session,item).encode();var restored=QueueExitPayload.decode(saved);
        assertArrayEquals(item,restored.original());assertTrue(restored.belongsTo(owner,session));
        assertFalse(restored.belongsTo(UUID.randomUUID(),session));assertFalse(restored.belongsTo(owner,UUID.randomUUID()));
    }
    @Test void emptySlotCanBeRestoredWithoutInventingAnItem(){
        var value=new QueueExitPayload(UUID.randomUUID(),UUID.randomUUID(),new byte[0]);
        assertEquals(0,QueueExitPayload.decode(value.encode()).original().length);
    }
    @Test void originalItemCannotBeChangedThroughCallerOwnedArrays(){
        byte[] source={1,2,3};var value=new QueueExitPayload(UUID.randomUUID(),UUID.randomUUID(),source);source[0]=9;
        byte[] read=value.original();read[1]=9;assertArrayEquals(new byte[]{1,2,3},value.original());
    }
    @Test void corruptedTruncatedOrOversizedControlsFailBeforeOriginalItemDecode(){
        byte[] saved=new QueueExitPayload(UUID.randomUUID(),UUID.randomUUID(),new byte[]{1,2,3}).encode();
        for(int index:new int[]{0,5,37,41,saved.length-1}){byte[] changed=saved.clone();changed[index]^=1;assertThrows(IllegalArgumentException.class,()->QueueExitPayload.decode(changed));}
        assertThrows(IllegalArgumentException.class,()->QueueExitPayload.decode(Arrays.copyOf(saved,saved.length-1)));
        assertThrows(IllegalArgumentException.class,()->QueueExitPayload.decode(new byte[4*1024*1024+73]));
    }
    @Test void exitControlOnlyBelongsToWaitingAndCountdownNeverToMatchOrRecoveryPhases(){
        for(var state:GameState.values())assertEquals(Set.of(GameState.WAITING,GameState.COUNTDOWN).contains(state),QueueExitPayload.queue(state),state.name());
        assertFalse(QueueExitPayload.queue(null));
    }
}
