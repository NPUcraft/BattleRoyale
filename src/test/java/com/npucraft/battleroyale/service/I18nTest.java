package com.npucraft.battleroyale.service;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class I18nTest {
    @AfterEach void clear(){I18n.close();}
    @Test void allChineseRegionsAndScriptsUseChineseOtherAndUnknownLocalesUseEnglish(){
        for(Locale locale:List.of(Locale.SIMPLIFIED_CHINESE,Locale.TRADITIONAL_CHINESE,Locale.forLanguageTag("zh-HK"),Locale.forLanguageTag("zh-Hant-TW"),Locale.forLanguageTag("zh-SG")))assertTrue(I18n.chinese(locale),locale.toString());
        for(Locale locale:List.of(Locale.ENGLISH,Locale.GERMAN,Locale.JAPANESE,Locale.ROOT,Locale.forLanguageTag("zz-ZZ")))assertFalse(I18n.chinese(locale),locale.toString());
        assertFalse(I18n.chinese((Locale)null));assertFalse(I18n.chinese((CommandSender)null));
    }
    @Test void selectsTemplatesWithoutParsingOrTranslatingArguments(){
        String input="<red>单人竞技 %s &a玩家";
        assertEquals("Room: "+input,I18n.text(Locale.GERMAN,"房间：%s","Room: %s",input));
        assertEquals("房间："+input,I18n.text(Locale.TRADITIONAL_CHINESE,"房间：%s","Room: %s",input));
        assertEquals(input,plain(UiText.value(input)));
    }
    @Test void messageServiceUsesLatestPlayerLocaleAndPreservesConsoleCompatibility(){
        var received=new ArrayList<Component>();Locale[] locale={Locale.ENGLISH};
        Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->{
            if(method.getName().equals("locale"))return locale[0];
            if(method.getName().equals("sendMessage")&&args!=null&&args.length==1&&args[0] instanceof Component c){received.add(c);return null;}
            if(method.getName().equals("hasPermission"))return true;
            return null;
        });
        var messages=new MessageService(Logger.getAnonymousLogger());messages.event(player,"joined","<red>自定义ID");
        assertEquals("[BattleRoyale] Joined room <red>自定义ID.",plain(received.getLast()));
        locale[0]=Locale.TRADITIONAL_CHINESE;messages.event(player,"joined","<red>自定义ID");
        assertEquals("[BattleRoyale] 已加入房间 <red>自定义ID。",plain(received.getLast()));
        CommandSender console=(CommandSender)Proxy.newProxyInstance(CommandSender.class.getClassLoader(),new Class<?>[]{CommandSender.class},(proxy,method,args)->null);
        assertTrue(I18n.chinese(console));locale[0]=null;assertFalse(I18n.chinese(player));
    }
    @Test void internalErrorsHaveBothDirectionsButArbitraryTextStaysLiteral(){
        assertEquals("你已加入一个房间，请先离开。",I18n.error(Locale.CHINESE,"Already in room"));
        assertEquals("Already in room",I18n.error(Locale.ENGLISH,"你已加入一个房间，请先离开。"));
        assertEquals("Unknown room: 自定义<red>",I18n.error(Locale.ENGLISH,"房间不存在：自定义<red>"));
        assertEquals("Purchased",I18n.error(Locale.GERMAN,"购买成功。"));
        assertEquals("原样姓名 %s",I18n.error(Locale.ENGLISH,"原样姓名 %s"));
        assertEquals("OK",I18n.state(Locale.ENGLISH,"OK"));
    }
    @Test void sharedTextPreservesLiteralStyledArgumentsAndFallsBackToEnglish(){
        Component name=UiText.value("<red>玩家 %s");
        Component value=I18n.shared("test.name","玩家 {0}","Player {0}",name).color(UiText.BODY);
        assertEquals("玩家 <red>玩家 %s",plain(GlobalTranslator.render(value,Locale.TRADITIONAL_CHINESE)));
        Component english=GlobalTranslator.render(value,Locale.GERMAN);
        assertEquals("Player <red>玩家 %s",plain(english));
        assertTrue(containsStyledName(english));
        assertThrows(IllegalArgumentException.class,()->I18n.shared("test.name","冲突","Conflict"));
        I18n.close();assertEquals(value,GlobalTranslator.render(value,Locale.ENGLISH));
        I18n.install();I18n.install();I18n.close();I18n.close();
    }
    @Test void persistedSharedTitlesRenderImmediatelyAfterFreshInstall(){
        Component saved=Component.translatable("battleroyale.airdrop.container",UiText.value("3"));
        I18n.close();I18n.install();
        assertEquals("Supply drop 3",plain(GlobalTranslator.render(saved,Locale.GERMAN)));
        assertEquals("第 3 轮补给空投",plain(GlobalTranslator.render(saved,Locale.TRADITIONAL_CHINESE)));
    }
    @Test void templateToneDoesNotDependOnPlayerNameAndEnglishWordsHaveBoundaries(){
        assertEquals(UiText.BODY,UiText.text((CommandSender)null,"玩家 %s","Player %s","failed").color());
        assertEquals(UiText.BODY,UiText.text("Player failedNinja").color());
        assertEquals(UiText.ERROR,UiText.text("Save failed.").color());
        assertEquals(UiText.SUCCESS,UiText.text("Loadout saved.").color());
    }
    private static boolean containsStyledName(Component value){return value instanceof TextComponent text&&text.content().equals("<red>玩家 %s")&&UiText.VALUE.equals(text.color())||value.children().stream().anyMatch(I18nTest::containsStyledName);}
    private static String plain(Component value){return PlainTextComponentSerializer.plainText().serialize(value);}
}
