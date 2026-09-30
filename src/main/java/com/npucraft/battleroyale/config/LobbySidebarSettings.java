package com.npucraft.battleroyale.config;

import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

/** Missing sidebar configuration enables the lobby display without authorizing any world construction. */
public record LobbySidebarSettings(boolean enabled,String title,int pageSeconds) {
    public static final LobbySidebarSettings DEFAULT=new LobbySidebarSettings(true,"大逃杀",8);
    public LobbySidebarSettings {
        Objects.requireNonNull(title);
        if(title.isBlank()||title.codePointCount(0,title.length())>32||title.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("sidebar.title 必须为1至32个字符的单行标题");
        if(pageSeconds<2||pageSeconds>60)throw new IllegalArgumentException("sidebar.page-seconds 必须为2至60秒");
    }
    public static LobbySidebarSettings load(ConfigurationSection yaml){
        if(yaml.contains("sidebar.enabled")&&!yaml.isBoolean("sidebar.enabled"))throw new IllegalArgumentException("sidebar.enabled 必须为 true 或 false");
        if(yaml.contains("sidebar.page-seconds")&&!yaml.isInt("sidebar.page-seconds"))throw new IllegalArgumentException("sidebar.page-seconds 必须为整数");
        if(yaml.contains("sidebar.title")&&!yaml.isString("sidebar.title"))throw new IllegalArgumentException("sidebar.title 必须为文字");
        return new LobbySidebarSettings(yaml.getBoolean("sidebar.enabled",true),yaml.getString("sidebar.title",DEFAULT.title()),yaml.getInt("sidebar.page-seconds",8));
    }
}
