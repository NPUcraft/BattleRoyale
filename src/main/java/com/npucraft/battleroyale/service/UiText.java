package com.npucraft.battleroyale.service;

import java.util.Objects;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Literal Adventure styling. This never parses player names or configuration as markup. */
public final class UiText {
    public static final TextColor BRAND = TextColor.color(0x46D9F7);
    public static final TextColor BODY = TextColor.color(0xD7E1E8);
    public static final TextColor VALUE = TextColor.color(0xFFD166);
    public static final TextColor SUCCESS = TextColor.color(0x71E09B);
    public static final TextColor ERROR = TextColor.color(0xFF737D);
    public static final TextColor WARNING = TextColor.color(0xFFAF62);
    public static final TextColor MUTED = TextColor.color(0x8495A7);

    private static final Pattern TOKENS = Pattern.compile(
        "(?<command>/[A-Za-z][A-Za-z0-9:_-]*(?:[ \\t]+(?:[A-Za-z0-9_.*|:=/-]+|<[^>\\r\\n]{1,80}>|\\[[^]\\r\\n]{1,80}\\]))*)"
        + "|(?<argument><[^>\\r\\n]{1,80}>)"
        + "|(?<state>重连超时|暂时离线|已淘汰|观战中|等待中|倒计时|准备中|开局中|比赛中|结算中|清理中|缩圈中|最终安全区|安全区内|存活|已装备|已拥有|未解锁|暂时无法加入|\\b(?:Alive|Inside zone|Equipped|Owned|Eliminated|Disconnected|Shrinking|Locked|Spectating|Waiting|Countdown|Preparing|Starting|In progress|Results|Cleaning up|Final zone)\\b)"
        + "|(?<number>(?<![A-Za-z0-9_])[-+]?\\d+(?:\\.\\d+)?(?:%|秒|米|金币)?(?![A-Za-z0-9_]))"
        + "|(?<identifier>(?<=[：=])[A-Za-z][A-Za-z0-9_.:/-]*)");

    private UiText() {}

    public static Component message(org.bukkit.command.CommandSender viewer,String zh,String en,Object... args){return message(I18n.text(viewer,zh,en,args),tone(I18n.text(viewer,zh,en)));}
    public static Component text(org.bukkit.command.CommandSender viewer,String zh,String en,Object... args){return styled(I18n.text(viewer,zh,en,args),tone(I18n.text(viewer,zh,en)),false);}
    public static Component heading(org.bukkit.command.CommandSender viewer,String zh,String en,Object... args){return heading(I18n.text(viewer,zh,en,args));}
    public static Component success(org.bukkit.command.CommandSender viewer,String zh,String en,Object... args){return success(I18n.text(viewer,zh,en,args));}
    public static Component warning(org.bukkit.command.CommandSender viewer,String zh,String en,Object... args){return warning(I18n.text(viewer,zh,en,args));}
    public static Component error(org.bukkit.command.CommandSender viewer,String zh,String en,Object... args){return error(I18n.text(viewer,zh,en,args));}
    public static Component value(org.bukkit.command.CommandSender viewer,String zh,String en,Object... args){return value(I18n.text(viewer,zh,en,args));}
    public static Component muted(org.bukkit.command.CommandSender viewer,String zh,String en,Object... args){return muted(I18n.text(viewer,zh,en,args));}

    /** Chat prefix keeps its existing plain text so consoles and diagnostics remain searchable. */
    public static Component message(String text) {
        return message(text,tone(text));
    }
    private static Component message(String text,TextColor base) {
        return Component.empty().decoration(TextDecoration.ITALIC, false)
            .append(Component.text("[", MUTED))
            .append(Component.text("BattleRoyale", BRAND).decorate(TextDecoration.BOLD))
            .append(Component.text("] ", MUTED))
            .append(styled(text,base,false));
    }

    /** Unprefixed prose with lightweight status, command, parameter and number accents. */
    public static Component text(String text) { return styled(text, tone(text), false); }
    public static Component heading(String text) { return styled(text, BRAND, true); }
    public static Component success(String text) { return styled(text, SUCCESS, false); }
    public static Component warning(String text) { return styled(text, WARNING, false); }
    public static Component error(String text) { return styled(text, ERROR, false); }
    public static Component muted(String text) { return literal(text, MUTED); }
    public static Component value(String text) { return literal(text, VALUE); }

    private static Component literal(String text, TextColor color) {
        return Component.text(Objects.requireNonNull(text), color)
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.BOLD, false);
    }

    private static Component styled(String text, TextColor base, boolean bold) {
        Objects.requireNonNull(text);
        var result = Component.text().color(base)
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.BOLD, bold);
        var matcher = TOKENS.matcher(text);
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() > end) result.append(Component.text(text.substring(end, matcher.start())));
            TextColor color = matcher.group("state") == null ? VALUE : stateColor(matcher.group());
            var token = Component.text(matcher.group(), color);
            if (matcher.group("command") != null) token = token.decorate(TextDecoration.BOLD);
            result.append(token);
            end = matcher.end();
        }
        if (end < text.length()) result.append(Component.text(text.substring(end)));
        return result.build();
    }

    private static TextColor stateColor(String state) {
        return switch (state) {
            case "存活", "安全区内", "已装备", "已拥有", "Alive", "Inside zone", "Equipped", "Owned" -> SUCCESS;
            case "已淘汰", "重连超时", "Eliminated" -> ERROR;
            case "暂时离线", "缩圈中", "未解锁", "暂时无法加入", "Disconnected", "Shrinking", "Locked" -> WARNING;
            default -> BRAND;
        };
    }

    private static TextColor tone(String text) {
        Objects.requireNonNull(text);
        if (contains(text, "失败", "错误", "你没有", "缺少权限", "未知命令", "不存在", "无效", "余额不足",
                "无法", "不可用", "不允许", "拒绝", "不受支持", "尚未加入", "超出边界", "人数已满", "未获批准", "failed", "error", "not allowed", "no permission", "do not have permission", "not found", "unavailable", "insufficient", "invalid", "rejected", "could not", "cannot", "unknown command")) return ERROR;
        if (contains(text, "成功", "已加入", "已离开", "已返回", "已保存", "已完成", "校验通过", "已就绪", "胜利", "获胜",
                "已装备", "已取消装备", "已更新", "已设置", "已移除", "已解锁", "success", "joined room", "left room", "returned", "saved", "completed", "victory", "your team won", "unlocked", "equipped")) return SUCCESS;
        if (contains(text, "警告", "正在", "请先", "请等待", "请稍候", "重连超时", "已断线", "暂时离线", "人数不足", "取消",
                "维护中", "待复核", "人工复核", "落地保护已结束", "重连时间已过", "warning", "please wait", "try again later", "disconnected", "cancelled", "not enough", "preparing", "in progress", "protection ended", "loading", "review required")) return WARNING;
        return BODY;
    }

    private static boolean contains(String text, String... phrases) {
        String normalized=text.toLowerCase(java.util.Locale.ROOT);
        for (String phrase : phrases) {
            String target=phrase.toLowerCase(java.util.Locale.ROOT);
            if(target.chars().allMatch(c->c<128)){
                for(int at=normalized.indexOf(target);at>=0;at=normalized.indexOf(target,at+1)){
                    int end=at+target.length();
                    if((at==0||!word(normalized.charAt(at-1)))&&(end==normalized.length()||!word(normalized.charAt(end))))return true;
                }
            }else if(normalized.contains(target))return true;
        }
        return false;
    }
    private static boolean word(char value){return value>='a'&&value<='z'||value>='0'&&value<='9'||value=='_';}
}
