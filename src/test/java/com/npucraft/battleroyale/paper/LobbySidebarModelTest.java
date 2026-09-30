package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.session.GameState;
import java.util.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LobbySidebarModelTest {
    private static final PlainTextComponentSerializer TEXT=PlainTextComponentSerializer.plainText();
    private LobbySidebarModel.RoomView room(String name,GameState state,int count,int countdown){return new LobbySidebarModel.RoomView(name,name,count,24,4,state,countdown);}
    private String text(LobbySidebarModel.Page page){return page.lines().stream().map(TEXT::serialize).collect(java.util.stream.Collectors.joining("\n"));}
    @Test void emptyListStillRendersUsefulOnlineCountAndNeverAnInvalidPage(){
        var page=LobbySidebarModel.page(List.of(),7,0,8);
        assertEquals(1,page.number());assertEquals(1,page.total());assertTrue(text(page).contains("在线：7"));assertTrue(text(page).contains("暂无"));assertEquals(3,page.lines().size());
    }
    @Test void allRoomsAppearDuringOneRotationAndEveryPageFitsSidebarLimit(){
        var rooms=new ArrayList<LobbySidebarModel.RoomView>();
        for(int i=0;i<12;i++)rooms.add(room(String.format("房间[%02d]",i),GameState.WAITING,i,-1));
        var pages=new ArrayList<LobbySidebarModel.Page>();for(int i=0;i<3;i++)pages.add(LobbySidebarModel.page(rooms,30,i*8L,8));
        for(int i=0;i<3;i++){assertEquals(i+1,pages.get(i).number());assertEquals(3,pages.get(i).total());assertTrue(pages.get(i).lines().size()<=15);}
        assertEquals(7,pages.get(0).lines().size());assertEquals(4,pages.get(2).lines().size());
        for(var room:rooms)assertEquals(1,pages.stream().filter(page->text(page).contains(room.name())).count());
        assertEquals(pages.get(0),LobbySidebarModel.page(rooms,30,24,8));
    }
    @Test void countdownComesFromRoomRemainingAndMissingTimerNeverDisplaysNegativeSeconds(){
        var waiting=LobbySidebarModel.page(List.of(room("双人",GameState.WAITING,2,-1)),9,0,8);
        assertTrue(text(waiting).contains("2/24 · 等待"));assertFalse(text(waiting).contains("开局"));assertFalse(text(waiting).contains("还需"));
        var countdown=LobbySidebarModel.page(List.of(room("双人",GameState.COUNTDOWN,4,12)),9,0,8);
        assertTrue(text(countdown).contains("4/24 · 12秒"));
        assertFalse(text(LobbySidebarModel.page(List.of(room("双人",GameState.COUNTDOWN,4,-1)),9,0,8)).contains("-1秒"));
        assertNotEquals(waiting,countdown);
    }
    @Test void ordinaryLobbyAndQueueVisibleButEveryActiveMatchPhaseHidden(){
        assertTrue(LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,true,false,false,false,false,false,null)));
        for(var state:GameState.values())assertEquals(state==GameState.WAITING||state==GameState.COUNTDOWN,
            LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,true,false,false,false,false,false,state)),state.name());
    }
    @Test void everyRestorationEditingSpectatingAndWorldGateHidesTheSidebar(){
        assertFalse(LobbySidebarModel.visible(new LobbySidebarModel.Audience(false,true,false,false,false,false,false,null)));
        assertFalse(LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,false,false,false,false,false,false,null)));
        assertFalse(LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,true,true,false,false,false,false,null)));
        assertFalse(LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,true,false,true,false,false,false,null)));
        assertFalse(LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,true,false,false,true,false,false,null)));
        assertFalse(LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,true,false,false,false,true,false,null)));
        assertFalse(LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,true,false,false,false,false,true,null)));
    }
    @Test void aLegitimateLobbyReturnCanKeepHistoricalMatchMembershipButNeverBypassSafetyGates(){
        for(var state:List.of(GameState.RUNNING,GameState.ENDING,GameState.CLEANUP)){
            assertTrue(LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,true,false,false,false,false,false,state,true)),state.name());
            assertFalse(LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,true,false,false,false,false,true,state,true)));
            assertFalse(LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,true,false,true,false,false,false,state,true)));
            assertFalse(LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,false,false,false,false,false,false,state,true)));
            assertFalse(LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,true,false,false,true,false,false,state,true)));
            assertFalse(LobbySidebarModel.visible(new LobbySidebarModel.Audience(true,true,false,false,false,true,false,state,true)));
        }
    }
    @Test void roomStatesHaveDistinctStatusColorsWithoutRecoloringOtherUi(){
        var colors=new HashSet<net.kyori.adventure.text.format.TextColor>();
        for(var state:List.of(GameState.WAITING,GameState.COUNTDOWN,GameState.PREPARING,GameState.RUNNING,GameState.ENDING,GameState.CLEANUP)){
            var page=LobbySidebarModel.page(List.of(room("房间",state,4,10)),4,0,8);colors.add(page.lines().get(1).children().getLast().color());
        }
        assertEquals(6,colors.size());assertFalse(colors.contains(null));
    }
    @Test void duplicateNamesRemainSeparateRowsAndControlCharactersCannotCreateExtraLines(){
        var page=LobbySidebarModel.page(List.of(room("重名\n房间",GameState.WAITING,1,-1),room("重名\n房间",GameState.COUNTDOWN,4,5)),5,0,8);
        assertEquals(4,page.lines().size());assertTrue(TEXT.serialize(page.lines().get(1)).contains("重名房间"));
        assertTrue(TEXT.serialize(page.lines().get(2)).contains("重名房间"));assertNotEquals(page.lines().get(1),page.lines().get(2));
    }
    @Test void threeRoomsUseFiveLinesAndClientLanguageOnlyChangesBuiltInLabels(){
        var rooms=List.of(room("Solo",GameState.WAITING,1,-1),room("双人组队",GameState.COUNTDOWN,4,12),room("Custom 我的房间",GameState.RUNNING,9,0));
        var english=LobbySidebarModel.page(rooms,14,0,8,Locale.GERMAN);var chinese=LobbySidebarModel.page(rooms,14,0,8,Locale.TRADITIONAL_CHINESE);
        assertEquals(5,english.lines().size());assertEquals(5,chinese.lines().size());
        assertTrue(text(english).contains("Online: 14"));assertTrue(text(english).contains("Solo  1/24 · Waiting"));assertTrue(text(english).contains("Duo  4/24 · 12s"));
        assertTrue(text(chinese).contains("单人竞技"));assertTrue(text(chinese).contains("12秒"));
        assertTrue(text(english).contains("Custom 我的房间"));assertTrue(text(chinese).contains("Custom 我的房间"));
        assertFalse(text(english).contains("━━"));assertTrue(text(english).contains("Compass"));
    }
}
