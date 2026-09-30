package com.npucraft.battleroyale.config;

import org.bukkit.configuration.ConfigurationSection;

/** Explicit construction boundary. Missing structure configuration never authorizes building. */
public record LobbySettings(boolean buildEnabled, String world, int x, int y, int z, int radius,
        int blocksPerTick, boolean clearExisting, boolean protection, boolean adminBuild, boolean returnOnJoin) {
    public static final LobbySettings DEFAULT = new LobbySettings(false, "battleroyale_lobby", 0,160,0,32,1200,false,true,false,true);
    public LobbySettings {
        if (world == null || world.isBlank() || world.contains("/") || world.contains("\\") || world.contains(":"))
            throw new IllegalArgumentException("大厅世界必须是明确的世界名称");
        if (Math.abs((long)x) > 29_999_000 || Math.abs((long)z) > 29_999_000 || y < -48 || y > 300)
            throw new IllegalArgumentException("大厅中心坐标超出安全范围");
        if (radius != 32) throw new IllegalArgumentException("内置大厅蓝图半径固定为32格");
        if (blocksPerTick < 100 || blocksPerTick > 4000) throw new IllegalArgumentException("大厅每tick施工量须为100至4000");
    }
    public static LobbySettings load(ConfigurationSection yaml) {
        return new LobbySettings(bool(yaml,"structure.enabled", false), yaml.getString("structure.world", "battleroyale_lobby"),
                integer(yaml,"structure.center.x",0), integer(yaml,"structure.center.y",160), integer(yaml,"structure.center.z",0),
                integer(yaml,"structure.radius",32), integer(yaml,"structure.blocks-per-tick",1200),
                bool(yaml,"structure.clear-existing-blocks",false), bool(yaml,"protection.enabled",true),
                bool(yaml,"protection.allow-admin-build",false), bool(yaml,"login.return-to-lobby",true));
    }
    private static boolean bool(ConfigurationSection yaml,String key,boolean fallback) {
        if (!yaml.contains(key)) return fallback;
        if (!yaml.isBoolean(key)) throw new IllegalArgumentException(key + " 必须为 true 或 false");
        return yaml.getBoolean(key);
    }
    private static int integer(ConfigurationSection yaml,String key,int fallback) {
        if (!yaml.contains(key)) return fallback;
        if (!yaml.isInt(key)) throw new IllegalArgumentException(key + " 必须为整数");
        return yaml.getInt(key);
    }
    public boolean within(int bx,int by,int bz) {
        return Math.abs((long)bx-x)<=radius && Math.abs((long)bz-z)<=radius && by>=y-3 && by<=y+10;
    }
}
