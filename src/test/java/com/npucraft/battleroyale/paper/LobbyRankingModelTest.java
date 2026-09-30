package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.progression.LobbyRankingRow;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LobbyRankingModelTest {
    private static String plain(Component text){return PlainTextComponentSerializer.plainText().serialize(text);}
    @Test void realRowsExposeRatingKillsWinsAndProfileEntrance(){
        var text=LobbyRankingModel.render(List.of(new LobbyRankingRow(UUID.randomUUID(),"Alice",1234,19,3)),LobbyRankingModel.Status.READY);
        assertTrue(plain(text).contains("1. Alice  Rating 1234  击杀 19  胜场 3"));
        assertTrue(plain(text).contains("右键下方讲台 · 查看我的数据"));assertNotNull(text.color());noEvents(text);
    }
    @Test void emptyAndUnavailableNeverInventRankings(){
        assertTrue(plain(LobbyRankingModel.render(List.of(),LobbyRankingModel.Status.READY)).contains("暂无玩家数据"));
        for(var status:LobbyRankingModel.Status.values())assertFalse(plain(LobbyRankingModel.render(List.of(),status)).contains("1. "));
        assertTrue(plain(LobbyRankingModel.render(List.of(),LobbyRankingModel.Status.UNAVAILABLE)).contains("稍后自动重试"));
    }
    @Test void failedRefreshLabelsRetainedRowsAndCapsHeight(){
        var rows=new ArrayList<LobbyRankingRow>();for(int i=1;i<=10;i++)rows.add(new LobbyRankingRow(UUID.randomUUID(),"Player"+i,i,i,i));
        String text=plain(LobbyRankingModel.render(rows,LobbyRankingModel.Status.STALE));
        assertTrue(text.contains("8. Player8"));assertFalse(text.contains("9. Player9"));assertTrue(text.contains("保留最近结果"));
    }
    @Test void namesStayLiteralAndCannotInjectRowsOrEvents(){
        var text=LobbyRankingModel.render(List.of(new LobbyRankingRow(UUID.randomUUID(),"<red>A\nB\rC\t&c",1000,0,0)),LobbyRankingModel.Status.READY);
        assertTrue(plain(text).contains("<red>ABC&c"));assertEquals(4,plain(text).chars().filter(c->c=='\n').count());noEvents(text);
    }
    private static void noEvents(Component text){assertNull(text.clickEvent());assertNull(text.hoverEvent());assertNull(text.insertion());text.children().forEach(LobbyRankingModelTest::noEvents);}
    @Test void englishRankingsTranslateLabelsButNeverPlayerNamesOrValues(){
        var rows=List.of(new LobbyRankingRow(UUID.randomUUID(),"单人竞技",1234,19,3));
        var english=LobbyRankingModel.render(rows,LobbyRankingModel.Status.READY,Locale.JAPANESE);
        assertTrue(plain(english).contains("1. 单人竞技  Rating 1234  Kills 19  Wins 3"));
        assertTrue(plain(english).contains("Right-click the lectern"));assertFalse(plain(english).contains("玩家荣誉榜"));noEvents(english);
        assertTrue(plain(LobbyRankingModel.render(List.of(),LobbyRankingModel.Status.READY,Locale.ENGLISH)).contains("No player stats yet"));
        assertTrue(plain(LobbyRankingModel.render(rows,LobbyRankingModel.Status.STALE,Locale.ENGLISH)).contains("showing latest results"));
    }
}
