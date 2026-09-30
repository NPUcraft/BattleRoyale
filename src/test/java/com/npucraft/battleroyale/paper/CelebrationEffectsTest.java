package com.npucraft.battleroyale.paper;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CelebrationEffectsTest {
    @Test void countdownIncludesSecondsAndEarlyReturnCommand(){
        var text=PlainTextComponentSerializer.plainText().serialize(CelebrationEffects.returnStatus(60));
        assertEquals("返回大厅倒计时：60 秒  |  /br leave 提前返回",text);
    }
    @Test void elapsedDeadlineShowsReturnInProgressRatherThanNegativeSeconds(){
        for(int seconds:new int[]{0,-1})assertEquals("结算结束，正在返回大厅……",PlainTextComponentSerializer.plainText().serialize(CelebrationEffects.returnStatus(seconds)));
    }
}
