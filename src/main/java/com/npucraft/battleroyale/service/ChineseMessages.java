package com.npucraft.battleroyale.service;

import java.util.Map;

/** Translates presentation text without changing domain identifiers or persisted state names. */
public final class ChineseMessages {
    private ChineseMessages() {}

    private static final Map<String, String> ERRORS = Map.ofEntries(
            Map.entry("Already in room", "你已加入一个房间，请先离开。"),
            Map.entry("No room available", "暂时没有可加入的房间。"),
            Map.entry("You are not in a room", "你尚未加入房间。"),
            Map.entry("Room is full", "房间人数已满。"),
            Map.entry("Room is not joinable", "该房间暂时不能加入。"),
            Map.entry("Leaving is only allowed while waiting or counting down", "只有等待中或倒计时阶段可以离开比赛。"),
            Map.entry("Player is not in this room", "你不在该房间中。"),
            Map.entry("Room cannot be started in its current state", "房间当前状态无法开始比赛。"),
            Map.entry("Only preparing, starting, running or ending sessions may be ended", "只能结束准备中、开局中、进行中或结算中的比赛。"),
            Map.entry("BattleRoyale is stopping", "BattleRoyale 正在关闭，请稍后再试。"),
            Map.entry("Recovery/checkpoint completion must finish before reload", "请等待恢复与检查点保存完成后再重载。"),
            Map.entry("Permanent data operations must finish before reload", "请等待永久数据保存完成后再重载。"),
            Map.entry("Map maintenance must finish before reload", "请结束地图维护后再重载。"),
            Map.entry("Cannot reload BattleRoyale while rooms or game sessions are active.", "房间仍有玩家或比赛尚未结束，暂时无法重载。"),
            Map.entry("Changing database settings requires a server restart", "修改数据库设置后需要重启服务器。"),
            Map.entry("Close loadout editors and wait for saves before reload", "请关闭装备编辑器并等待保存完成后再重载。"),
            Map.entry("Explicit remove confirmation required", "移除操作需要明确确认，请在命令末尾添加 confirm。"),
            Map.entry("Metadata limit: 1024 containers / 128 areas", "地图最多允许 1024 个容器点和 128 个地面区域。"),
            Map.entry("Spectator position outside playable area", "观战点位于可玩区域之外。"),
            Map.entry("Spectator fallback uses zone/world spawn", "未设置观战点，将使用安全区或世界出生点。"),
            Map.entry("Metadata valid", "地图配置校验通过。"),
            Map.entry("Invalid spectator position", "观战点位置无效，请检查坐标、朝向和高度。"),
            Map.entry("Unsupported map metadata/version", "地图配置或版本不受支持。"),
            Map.entry("Invalid loot area bounds/chance/spawns/attempts (limits 256)", "物资区域的边界、概率、数量或尝试次数无效（数量与尝试次数最多为 256）。"),
            Map.entry("Prepared chunk unloaded", "准备好的区块已卸载，请重试。"),
            Map.entry("Starter disconnected before landing", "玩家在进入比赛前离线。"),
            Map.entry("Safe spawn teleport rejected", "传送到安全出生点失败。"),
            Map.entry("Match cancelled during initial sanitation", "比赛已在世界初始化期间取消。"),
            Map.entry("Disconnected before durable preparation completed", "玩家在比赛准备完成前离线。"),
            Map.entry("Reconnect teleport rejected", "重连传送失败，请重试。"),
            Map.entry("Purchase already in progress", "购买正在处理，请勿重复点击。"),
            Map.entry("Economy unavailable", "经济服务暂不可用。"),
            Map.entry("Insufficient balance", "余额不足。"),
            Map.entry("Already owned", "你已经拥有此外观。"),
            Map.entry("Purchased", "购买成功。"),
            Map.entry("Withdrawal declined", "扣款未获批准，购买已取消。"),
            Map.entry("Purchase failed; refunded", "购买失败，已退还费用。"),
            Map.entry("Unlocked", "解锁成功。"),
            Map.entry("Equipped", "已装备。"),
            Map.entry("Unequipped", "已取消装备。")
            ,Map.entry("The previous match result is being saved. Please wait.","上一局的结果正在保存，请稍候。")
            ,Map.entry("Your profile is unavailable. Please try again later.","个人档案暂不可用，请稍后重试。")
            ,Map.entry("Your BattleRoyale profile is loading. Please wait.","你的 BattleRoyale 档案正在加载，请稍候。")
            ,Map.entry("Player data is not ready yet.","玩家数据尚未就绪")
            ,Map.entry("Cosmetics changed. Please reopen the shop.","外观配置已变更，请重新打开商店。")
            ,Map.entry("The paid shop is unavailable: economy is not ready.","付费商店暂不可用：经济服务未就绪。")
            ,Map.entry("Cosmetic not found.","外观不存在。")
            ,Map.entry("Please close the current editor first.","请先关闭当前编辑器。")
            ,Map.entry("Put away the item on your cursor before opening the editor.","请先放回鼠标光标上的物品，再打开编辑器。")
            ,Map.entry("Another save is in progress. Please try again later.","另一项保存正在进行，请稍后重试。")
            ,Map.entry("BattleRoyale is recovering matches. Please wait.","BattleRoyale 正在恢复比赛，请稍候。")
            ,Map.entry("Recovery storage is unavailable. The match cannot start.","恢复存储暂不可用，无法开始比赛。")
            ,Map.entry("Please close the map editor first.","请先关闭地图编辑器。")
            ,Map.entry("Wait for your original player state to finish restoring before joining.","请等待玩家原始状态恢复完成后再加入。")
            ,Map.entry("Please stop spectating first.","请先退出观战。")
            ,Map.entry("Wait for your previous match state to finish restoring before joining.","请等待上一局的玩家状态恢复完成后再加入。")
            ,Map.entry("You can return early only during the post-match celebration.","只能在比赛结束后的展示阶段提前返回大厅。")
            ,Map.entry("Recovery or storage is not ready. Please try returning to the lobby later.","恢复或存储暂未就绪，请稍后再返回大厅。")
            ,Map.entry("This room does not allow external spectators.","该房间不允许外部观战。")
            ,Map.entry("You can spectate only matches in progress or showing results.","只能观战进行中或结算中的比赛。")
            ,Map.entry("Please leave your current match or stop spectating first.","请先离开当前比赛或退出观战。")
            ,Map.entry("Could not start spectating. Your original state was retained.","进入观战失败，已保留原始状态。")
            ,Map.entry("Could not start spectating. Returning to the lobby.","进入观战失败，正在返回大厅。")
            ,Map.entry("Spectator teleport was rejected.","观战传送被拒绝。")
            ,Map.entry("The match is preparing or running. You cannot leave early; eliminated players may stop spectating.","比赛正在准备或进行中，不能提前退出；淘汰后可退出观战。")
            ,Map.entry("The lobby is still building or saving. Wait before reloading.","大厅仍在施工或保存，请完成后再重载。")
            ,Map.entry("The exit button belongs to another player. Item replacement was blocked.","退出按钮不属于当前玩家，已阻止物品覆盖。")
            ,Map.entry("The original item is too large to store in an exit button.","原物品数据过大，无法显示退出按钮。")
            ,Map.entry("Invalid exit button data length. The item was retained.","退出按钮数据长度无效，已保留物品。")
            ,Map.entry("Exit button checksum failed. The item was retained.","退出按钮数据校验失败，已保留物品。")
            ,Map.entry("Invalid exit button version. The item was retained.","退出按钮版本无效，已保留物品。")
            ,Map.entry("Invalid original item data in the exit button. The item was retained.","退出按钮原物品数据无效，已保留物品。")
            ,Map.entry("Could not read the exit button. The item was retained.","无法读取退出按钮，已保留物品。")
    );

    private static final Map<String, String> PREFIXES = Map.ofEntries(
            Map.entry("Unknown room: ", "房间不存在："),
            Map.entry("Room does not exist: ", "房间不存在："),
            Map.entry("Map is under maintenance: ", "地图正在维护："),
            Map.entry("Playable area cannot contain InitialZone for room ", "可玩区域无法容纳该房间的初始安全区："),
            Map.entry("Duplicate id: ", "重复的标记 ID："),
            Map.entry("Duplicate container block: ", "重复的容器方块："),
            Map.entry("Unknown LootTable: ", "物资表不存在："),
            Map.entry("Container outside bounds: ", "容器点超出边界："),
            Map.entry("Ground area outside bounds: ", "地面物资区域超出边界："),
            Map.entry("Template validation: ", "模板校验失败："),
            Map.entry("Purchase uncertain; administrator review required: ", "购买结果尚未确定，请联系管理员复核，交易编号："),
            Map.entry("Purchase unresolved; administrator review required: ", "购买尚未完成核对，请联系管理员复核，交易编号："),
            Map.entry("Purchase requires administrator review: ", "本次购买需要管理员复核，交易编号："),
            Map.entry("Refund unresolved; administrator review required: ", "退款状态尚未确定，请联系管理员复核，交易编号：")
            ,Map.entry("Loadout not found: ","装备方案不存在：")
            ,Map.entry("This loadout is being edited or saved: ","此装备方案正在编辑或保存：")
            ,Map.entry("Configured lobby world is not loaded: ","配置的大厅世界尚未加载：")
    );

    public static String text(String message) {
        if (message == null || message.isBlank()) return "操作未完成，请稍后重试或联系管理员。";
        String translated = ERRORS.get(message);
        if (translated != null) return translated;
        for (var entry : PREFIXES.entrySet())
            if (message.startsWith(entry.getKey())) return entry.getValue() + message.substring(entry.getKey().length());
        return message;
    }

    /** Reverse only complete known internal messages/prefixes; arbitrary user text stays unchanged. */
    public static String english(String message) {
        if(message==null||message.isBlank())return "The operation could not be completed. Try again or contact an administrator.";
        for(var entry:ERRORS.entrySet())if(message.equals(entry.getValue()))return entry.getKey();
        if(message.startsWith("房间不存在："))return "Unknown room: "+message.substring("房间不存在：".length());
        for(var entry:PREFIXES.entrySet())if(message.startsWith(entry.getValue()))return entry.getKey()+message.substring(entry.getValue().length());
        return message;
    }

    public static String state(Object state) {
        return switch (String.valueOf(state)) {
            case "WAITING" -> "等待中";
            case "COUNTDOWN" -> "倒计时";
            case "PREPARING" -> "准备中";
            case "STARTING" -> "开局中";
            case "RUNNING" -> "比赛中";
            case "ENDING" -> "结算中";
            case "CLEANUP" -> "清理中";
            case "ALIVE" -> "存活";
            case "DISCONNECTED" -> "暂时离线";
            case "ELIMINATED" -> "已淘汰";
            case "SPECTATING" -> "观战中";
            case "SHRINKING" -> "缩圈中";
            case "FINAL" -> "最终安全区";
            case "EDITING" -> "编辑";
            case "VALIDATING_DEEP" -> "深度校验";
            case "PREGENERATING" -> "预生成";
            case "ERROR" -> "错误";
            case "WARNING", "WARN" -> "警告";
            case "INFO" -> "提示";
            case "OK", "AVAILABLE" -> "正常";
            default -> String.valueOf(state);
        };
    }

    public static String mapTool(String key) {
        return switch (key) {
            case "area" -> "地图边界选择器";
            case "container" -> "容器物资设置器";
            case "ground" -> "地面物资区域选择器";
            case "spectator" -> "观战点设置器";
            case "inspect" -> "地图检查器";
            case "menu" -> "地图编辑菜单";
            default -> key;
        };
    }
}
