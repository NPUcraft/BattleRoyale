package com.npucraft.battleroyale.paper;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LobbyTextTest {
    @Test void onlyExactDefaultsTranslate(){
        assertEquals("Solo",LobbyText.defaultLabel(Locale.ENGLISH,"单人竞技"));
        assertEquals("单人竞技",LobbyText.defaultLabel(Locale.TRADITIONAL_CHINESE,"Solo"));
        assertEquals("BattleRoyale · My stats",LobbyText.defaultLabel(Locale.GERMAN,"大逃杀 · 我的战绩"));
        assertEquals("Custom Solo 单人竞技 <red>",LobbyText.defaultLabel(Locale.ENGLISH,"Custom Solo 单人竞技 <red>"));
        assertEquals("玩家命名的商店",LobbyText.defaultLabel(Locale.ENGLISH,"玩家命名的商店"));
    }
    @Test void defaultInventoryAndCosmeticDescriptionsHaveEnglish(){
        assertEquals("Quick join",LobbyText.defaultLabel(Locale.ROOT,"快速加入"));
        assertEquals("Victory fireworks",LobbyText.defaultLabel(Locale.ENGLISH,"胜利烟花"));
        assertEquals("Visual lightning without damage.",LobbyText.defaultLabel(Locale.ENGLISH,"仅供观赏的无伤闪电。"));
    }
}
