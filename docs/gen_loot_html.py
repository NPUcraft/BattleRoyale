# -*- coding: utf-8 -*-
"""Generate a Chinese HTML view of the ACTIVE loot tables only (legacy `airdrop` excluded)."""
import io, yaml, html

SRC = "D:/fwq/BattleRoyale/live-config/loot-tables-live.yml"
OUT = "D:/fwq/BattleRoyale/docs/loot-tables.html"

CN = {
    "minecraft:bread": "面包", "minecraft:apple": "苹果", "minecraft:arrow": "箭",
    "minecraft:spectral_arrow": "光谱箭", "minecraft:iron_sword": "铁剑", "minecraft:iron_axe": "铁斧",
    "minecraft:crossbow": "弩", "minecraft:shield": "盾牌", "minecraft:iron_chestplate": "铁胸甲",
    "minecraft:iron_leggings": "铁护腿", "minecraft:iron_boots": "铁靴子", "minecraft:iron_helmet": "铁头盔",
    "minecraft:golden_apple": "金苹果", "minecraft:diamond_sword": "钻石剑", "minecraft:cobblestone": "圆石",
    "minecraft:oak_planks": "橡木木板", "minecraft:iron_pickaxe": "铁镐", "minecraft:tnt": "TNT",
    "minecraft:water_bucket": "水桶", "minecraft:flint_and_steel": "打火石", "minecraft:ender_pearl": "末影珍珠",
    "minecraft:oak_log": "橡木原木", "minecraft:stone": "石头", "minecraft:iron_ingot": "铁锭",
    "minecraft:diamond": "钻石", "minecraft:stone_sword": "石剑", "minecraft:stone_axe": "石斧",
    "minecraft:stone_pickaxe": "石镐", "minecraft:leather_helmet": "皮革头盔", "minecraft:leather_chestplate": "皮革胸甲",
    "minecraft:leather_leggings": "皮革护腿", "minecraft:leather_boots": "皮革靴子",
    "minecraft:copper_helmet": "铜头盔", "minecraft:copper_chestplate": "铜胸甲", "minecraft:copper_leggings": "铜护腿",
    "minecraft:copper_boots": "铜靴子", "minecraft:chainmail_helmet": "锁链头盔", "minecraft:chainmail_chestplate": "锁链胸甲",
    "minecraft:chainmail_leggings": "锁链护腿", "minecraft:chainmail_boots": "锁链靴子",
    "minecraft:diamond_helmet": "钻石头盔", "minecraft:diamond_chestplate": "钻石胸甲",
    "minecraft:diamond_leggings": "钻石护腿", "minecraft:diamond_boots": "钻石靴子",
    "minecraft:iron_spear": "铁矛", "minecraft:stone_spear": "石矛", "minecraft:diamond_spear": "钻石矛",
    "minecraft:trident": "三叉戟", "minecraft:mace": "重锤", "minecraft:wind_charge": "风弹",
    "minecraft:fire_charge": "火焰弹", "minecraft:fishing_rod": "钓鱼竿", "minecraft:chorus_fruit": "紫颂果",
    "minecraft:snowball": "雪球", "minecraft:bow": "弓", "minecraft:string": "线", "minecraft:flint": "燧石",
    "minecraft:totem_of_undying": "不死图腾", "minecraft:netherite_ingot": "下界合金锭",
    "minecraft:netherite_upgrade_smithing_template": "下界合金锻造模板",
    "minecraft:silence_armor_trim_smithing_template": "寂静盔甲纹饰模板",
    "battleroyale:combat_firework": "战斗烟花", "battleroyale:invisibility_potion": "隐形药水",
    "battleroyale:poison_potion": "剧毒药水", "battleroyale:splash_invisibility_potion": "喷溅隐形药水",
    "battleroyale:splash_fire_resistance_potion": "喷溅防火药水", "battleroyale:splash_healing_potion": "喷溅治疗药水",
    "battleroyale:splash_harming_potion": "喷溅伤害药水", "battleroyale:splash_poison_potion": "喷溅剧毒药水",
    "battleroyale:signal_gun": "信号枪", "battleroyale:coin_100": "金币 ×100", "battleroyale:coin_1000": "金币 ×1000",
    "battleroyale:knockback_stick": "击退棒", "battleroyale:throwing_torch": "投掷火把",
    "battleroyale:sneakers": "疾风鞋", "battleroyale:mystery_food": "神秘食物",
    "battleroyale:slowness_arrow": "迟缓箭", "battleroyale:levitation_arrow": "漂浮箭",
}

# channel -> (label, description, table id used)
CHANNELS = [
    ("地面补给 · 开局（郊区）", "开局时在地图上撒点；郊区地形用这张。粒子颜色：青绿。", "ground-early", "low"),
    ("地面补给 · 开局（村庄等人造建筑区）", "开局时建筑密集区直接用中级表撒点。粒子颜色：琥珀。", "ground-mid", "mid"),
    ("地面补给 · 缩圈一刷（阶段1后，郊区）", "第一次缩圈开始后在未来安全区补 12 个点。建筑区改用 ground-late。", "ground-mid", "mid"),
    ("地面补给 · 缩圈二刷（阶段2后，郊区）", "第二次缩圈后补 10 个点。建筑区改用 ground-late。", "ground-mid", "mid"),
    ("地面补给 · 终盘（阶段3后）", "第三次缩圈后补 8 个点，郊区/建筑区都用这张高级表。粒子颜色：绯红。", "ground-late", "high"),
    ("空投 · 第 1 轮", "保底档位：铁器。公示 60 秒 + 下落 20 秒。", "airdrop-1", "air1"),
    ("空投 · 第 2 轮", "保底档位：铁中带钻（钻盔/钻剑/钻石）。", "airdrop-2", "air2"),
    ("空投 · 第 3 轮", "保底档位：钻石（含下界合金锭、锻造模板、特殊箭）。", "airdrop-3", "air3"),
    ("空投 · 第 4 轮", "保底档位：钻满配（含寂静纹饰、重锤、三叉戟）。", "airdrop-4", "air4"),
    ("野怪掉落", "击杀野怪 35% 概率掉落，每人 10 秒冷却，每场上限 64 次；每次摇 3–5 项。", "survival", None),
    ("原生箱子 · 基础注入", "地图自带的箱子有 15% 概率被注入 1–2 件基础生存物资。", "native-basic", None),
    ("原生箱子 · 区域品质替换（自然区域）", "自然区域（built 占比 < 25%）的原生箱子可能整箱换成这张表。", "region-natural", None),
    ("原生箱子 · 区域品质替换（建筑区域）", "建筑密集区（built 占比 ≥ 25%）的原生箱子可能整箱换成这张表。", "region-built", None),
]

TIER_COLORS = {
    "low": ("青绿 · 初级", "#2dd4a7"), "mid": ("琥珀 · 中级", "#e8a33d"), "high": ("绯红 · 高级", "#e05252"),
    "air1": ("空投 1 · 铁", "#b8c2cc"), "air2": ("空投 2 · 铁带钻", "#7ec8e3"),
    "air3": ("空投 3 · 钻", "#5ad2c2"), "air4": ("空投 4 · 钻满配", "#c07ef0"),
}

def fmt_amount(e):
    lo, hi = e.get("min-amount", 1), e.get("max-amount", 1)
    if lo == hi: return f"×{lo}"
    return f"×{lo}–{hi}"

data = yaml.safe_load(io.open(SRC, encoding="utf-8"))
tables = data["loot-tables"]

css = """
:root{--bg:#f6f7f9;--card:#ffffff;--ink:#1c2330;--muted:#6b7686;--line:#e3e7ee;--accent:#2f6fed}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);
font-family:"Segoe UI","Microsoft YaHei",system-ui,sans-serif;font-size:14px;line-height:1.55}
.wrap{max-width:1080px;margin:0 auto;padding:28px 20px 60px}
h1{font-size:22px;margin:0 0 4px}.sub{color:var(--muted);font-size:13px;margin-bottom:20px}
.toc{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 18px;margin-bottom:24px}
.toc a{color:var(--accent);text-decoration:none;margin-right:16px;font-size:13px;white-space:nowrap;display:inline-block}
.toc a:hover{text-decoration:underline}
.sec{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:18px 20px;margin-bottom:22px}
.sec h2{font-size:16px;margin:0 0 2px;display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.sec .desc{color:var(--muted);font-size:13px;margin:2px 0 12px}
.chip{font-size:11px;font-weight:600;color:#fff;border-radius:99px;padding:2px 10px}
.meta{font-size:12px;color:var(--muted);margin-bottom:10px}
table{width:100%;border-collapse:collapse;font-size:13px}
th{ text-align:left;color:var(--muted);font-weight:600;font-size:12px;border-bottom:1px solid var(--line);padding:6px 8px}
td{border-bottom:1px solid var(--line);padding:6px 8px;vertical-align:middle}
tr:last-child td{border-bottom:none}
td.num,th.num{text-align:right;font-variant-numeric:tabular-nums}
.bar{height:6px;border-radius:4px;background:var(--accent);opacity:.75;min-width:2px}
.code{font-family:Consolas,monospace;font-size:11.5px;color:var(--muted)}
.note{background:#fff8e6;border:1px solid #f0dc9a;border-radius:8px;padding:10px 14px;font-size:13px;margin-bottom:22px}
"""

parts = ["""<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>战利品表 · 现役版本</title><style>%s</style></head><body><div class="wrap">
<h1>战利品表（现役版本）</h1>
<div class="sub">来源：服务器 /plugins/BattleRoyale/loot-tables.yml（config-version 2）· 生成于 2026-10-03 22:53 · 概率 = 单项权重 ÷ 表内总权重</div>
<div class="note">已排除废弃表：<span class="code">airdrop</span>（旧的基础空投表，已被 <span class="code">round-tables: [airdrop-1…4]</span> 分轮阶梯取代，运行时不再读取）。共 9 张现役表、13 个投放渠道。</div>
<div class="toc">""" % css]

for label, _, tid, _tier in CHANNELS:
    parts.append(f'<a href="#{tid}">{html.escape(label.split(" · ")[0])} · {tid}</a>')
parts.append("</div>")

for label, desc, tid, tier in CHANNELS:
    t = tables[tid]
    entries = t["entries"]
    total = sum(e.get("weight", 1) for e in entries)
    rolls = f'{t.get("min-rolls",1)}–{t.get("max-rolls",1)}'
    chip = ""
    if tier:
        name, color = TIER_COLORS[tier]
        chip = f'<span class="chip" style="background:{color}">{name}</span>'
    parts.append(f'<div class="sec" id="{tid}"><h2>{html.escape(label)}{chip}</h2>'
                 f'<div class="desc">{html.escape(desc)}</div>'
                 f'<div class="meta">每次摇取 <b>{rolls}</b> 项 · 共 {len(entries)} 种条目 · 总权重 {total}</div>'
                 f'<table><tr><th style="width:30%">物品</th><th>数量</th><th class="num">权重</th>'
                 f'<th class="num">单抽概率</th><th style="width:22%">占比</th></tr>')
    for e in sorted(entries, key=lambda x: -x.get("weight", 1)):
        iid = e["item"]
        name = CN.get(iid, iid.split(":", 1)[1].replace("_", " "))
        w = e.get("weight", 1)
        pct = w / total * 100
        parts.append(f'<tr><td><b>{html.escape(name)}</b><br><span class="code">{html.escape(iid)}</span></td>'
                     f'<td>{fmt_amount(e)}</td><td class="num">{w}</td>'
                     f'<td class="num">{pct:.1f}%%</td>'
                     f'<td><div class="bar" style="width:{pct:.1f}%%"></div></td></tr>')
    parts.append("</table></div>")

parts.append("</div></body></html>")
io.open(OUT, "w", encoding="utf-8", newline="\n").write("".join(parts).replace("%%", "%"))
print("written", OUT)
