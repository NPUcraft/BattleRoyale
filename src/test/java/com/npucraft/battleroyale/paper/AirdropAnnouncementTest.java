package com.npucraft.battleroyale.paper;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AirdropAnnouncementTest {
    @Test void fullSixtySecondsStartAtAnnouncementNotAtTheAlreadyElapsedZonePhase() {
        var notice=new PaperAirdrops.Announcement(0,10,81,-20,120_000_000_000L);
        assertEquals(60,notice.remainingSeconds(120_000_000_000L));
        assertFalse(notice.ready(179_999_999_999L));assertEquals(1,notice.remainingSeconds(179_999_999_999L));
        assertTrue(notice.ready(180_000_000_000L));assertEquals(0,notice.remainingSeconds(180_000_000_000L));
        assertTrue(notice.ready(240_000_000_000L));assertEquals(0,notice.remainingSeconds(240_000_000_000L));
    }
    @Test void negativeNanoTimeOriginStillWaitsTheEntireMinute() {
        var notice=new PaperAirdrops.Announcement(2,-15,81,8,-100_000_000_000L);
        assertFalse(notice.ready(-40_000_000_001L));assertTrue(notice.ready(-40_000_000_000L));
    }
    @Test void noticeDisclosesCoordinatesAndTimingButNotTheRewards() {
        var notice=new PaperAirdrops.Announcement(1,-15,81,8,0);
        assertEquals("第 2 轮空投预告：X=-15 Y=81 Z=8，60 秒后开始降落。",PaperAirdrops.announcementText(notice));
        assertEquals("X=-15 Y=81 Z=8",notice.coordinates());
        assertFalse(PaperAirdrops.announcementText(notice).contains("钻石"));
        assertFalse(PaperAirdrops.announcementText(notice).contains("图腾"));
        assertFalse(PaperAirdrops.announcementText(notice).contains("奖励"));
    }
}
