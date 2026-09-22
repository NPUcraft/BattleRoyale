package com.npucraft.lastsector.paper;
import com.npucraft.lastsector.cosmetic.*;
import com.npucraft.lastsector.config.ProgressionConfig;
import com.npucraft.lastsector.death.DeathBox;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
/** Bounded visual-only effects; no combat or inventory writes. */
public final class PaperCosmeticEffects {
    private final JavaPlugin plugin;private final ProgressionConfig config;
    private final Map<UUID,Set<UUID>> entities=new HashMap<>();
    public PaperCosmeticEffects(JavaPlugin plugin,ProgressionConfig config){this.plugin=plugin;this.config=config;}
    private CosmeticDefinition definition(PaperMatches.Entry entry,UUID player,CosmeticCategory category) {
        if(entry.progress==null)return null;var frozen=entry.progress.frozen(player);if(frozen==null)return null;
        var id=frozen.cosmetics().equipped().get(category);return id==null?null:config.cosmetics().get(id);
    }
    public void win(PaperMatches.Entry entry,CelebrationEffects celebrations) {
        celebrations.fire(entry.session,winner->{var effect=definition(entry,winner,CosmeticCategory.WIN_EFFECT);
            return effect==null?Color.YELLOW:Color.fromRGB(Integer.parseInt(effect.effectConfig().getOrDefault("color","FFFF00"),16));});
    }
    public Material skin(PaperMatches.Entry entry,UUID deceased) {
        var definition=definition(entry,deceased,CosmeticCategory.DEATHBOX_SKIN);return definition==null?Material.BARREL:Material.valueOf(definition.effectConfig().get("material"));
    }
    public void kill(PaperMatches.Entry entry,DeathBox box) {
        UUID killer=box.reason().killer().orElse(null);if(killer==null || !entry.session.players().containsKey(killer))return;
        var effect=definition(entry,killer,CosmeticCategory.KILL_EFFECT);if(effect==null)return;
        var world=plugin.getServer().getWorld(box.location().world());if(world==null)return;
        var at=new Location(world,box.location().x(),box.location().y()+.5,box.location().z());
        if(effect.effectType().equals("visual_lightning")) {
            var lightning=world.strikeLightningEffect(at);RecoveryEntityCleaner.mark(lightning);
            lightning.getPersistentDataContainer().set(new NamespacedKey(plugin,"celebration_session"),PersistentDataType.STRING,entry.session.sessionId().toString());
            lightning.setSilent(true);lightning.setPersistent(false);entities.computeIfAbsent(entry.session.sessionId(),id->new HashSet<>()).add(lightning.getUniqueId());
            plugin.getServer().getScheduler().runTaskLater(plugin,()->{lightning.remove();var owned=entities.get(entry.session.sessionId());if(owned!=null)owned.remove(lightning.getUniqueId());},20);
        }else {
            Particle particle=effect.effectType().equals("firework_burst")?Particle.FIREWORK:Particle.valueOf(effect.effectConfig().getOrDefault("particle","HAPPY_VILLAGER"));
            for(var viewer:world.getPlayers())viewer.spawnParticle(particle,at,12,.3,.3,.3,.01);
        }
    }
    public void close(UUID session){for(UUID id:entities.getOrDefault(session,Set.of())){var entity=plugin.getServer().getEntity(id);if(entity!=null)entity.remove();}entities.remove(session);}
}
