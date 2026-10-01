package com.npucraft.battleroyale.flight;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TemporaryChestSlotTest {
    @Test void originalIsDefensivelyCopiedAndRestoredExactlyOnce(){
        var owner=UUID.randomUUID();var session=UUID.randomUUID();byte[] source={1,2,3};
        var slot=new TemporaryChestSlot(owner,session,source);source[0]=9;
        assertArrayEquals(new byte[]{1,2,3},slot.release(owner,session).orElseThrow());
        assertFalse(slot.active());assertTrue(slot.release(owner,session).isEmpty());
    }
    @Test void wrongOwnerCannotConsumeLease(){
        var owner=UUID.randomUUID();var session=UUID.randomUUID();var slot=new TemporaryChestSlot(owner,session,new byte[0]);
        assertThrows(IllegalArgumentException.class,()->slot.release(UUID.randomUUID(),session));
        assertTrue(slot.active());assertEquals(0,slot.release(owner,session).orElseThrow().length);
    }
}