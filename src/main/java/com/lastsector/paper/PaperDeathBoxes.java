package com.lastsector.paper;
import com.lastsector.death.*;
import com.lastsector.session.GameSession;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.function.Function;

/** Session-owned shared inventories and visual identities. Every access is revalidated. */
public final class PaperDeathBoxes implements AutoCloseable {
    private final JavaPlugin plugin;
    private final GameSession session;
    private final double reach;
    private final NativeItemSerializer serializer;
    private final StoredExperienceBottles experience;
    private final PaperDeathBoxVisuals visuals;
    private final Map<UUID,View> boxes=new LinkedHashMap<>();
    private final Map<UUID,View> entities=new HashMap<>();
    private final Set<Long> chunks=new HashSet<>();
    private final UUID worldId;
    public PaperDeathBoxes(JavaPlugin plugin,GameSession session,double reach,NativeItemSerializer serializer,StoredExperienceBottles experience) {
        this.plugin=plugin; this.session=session; this.reach=reach; this.serializer=serializer; this.experience=experience; visuals=new PaperDeathBoxVisuals(plugin);
        worldId=Objects.requireNonNull(plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName())).getUID();
    }
    public void create(DeathBox box,Function<UUID,String> names) {
        if(boxes.containsKey(box.id())) return;
        List<ItemStack> items=new ArrayList<>(); box.contents().forEach(i->items.add(serializer.item(i)));
        if(box.storedXp()>0) items.add(experience.create(box.storedXp()));
        View view=new View(this,box); for(int i=0;i<items.size();i++) view.inventory.setItem(i,items.get(i));
        boxes.put(box.id(),view);
        World world=Objects.requireNonNull(plugin.getServer().getWorld(worldId));
        int cx=((int)Math.floor(box.location().x()))>>4,cz=((int)Math.floor(box.location().z()))>>4;
        long key=Chunk.getChunkKey(cx,cz); if(chunks.add(key)) PaperChunkTickets.acquire(plugin,world,cx,cz);
        long seconds=box.elapsedNanos()/1_000_000_000L;
        Component label=Component.text(box.deceasedName()).appendNewline().append(DeathReasonRenderer.render(box.reason(),names))
                .appendNewline().append(Component.text("T+%02d:%02d".formatted(seconds/60,seconds%60)));
        view.visuals=visuals.spawn(box,label); view.visuals.forEach(id->entities.put(id,view));
    }
    public View visual(Entity entity) {
        View view=entities.get(entity.getUniqueId()); return view!=null && visuals.marked(entity,view.box)?view:null;
    }
    public boolean allowed(View view,Player player) {
        var at=player.getLocation();
        return view.valid && boxes.get(view.box.id())==view && !player.isDead()
                && DeathBoxAccess.allowed(session,view.box,player.getUniqueId(),new DeathPosition(at.getWorld().getUID(),at.getX(),at.getY(),at.getZ()),reach);
    }
    public void open(Entity entity,Player player) {
        View view=visual(entity); if(view!=null && allowed(view,player) && player.getOpenInventory().getTopInventory()!=view.inventory) player.openInventory(view.inventory);
    }
    public void closeViewers() {
        for(View view:boxes.values()) for(var viewer:List.copyOf(view.inventory.getViewers())) viewer.closeInventory();
    }
    @Override public void close() {
        closeViewers();
        for(View view:boxes.values()) {view.valid=false;visuals.remove(view.visuals);view.inventory.clear();}
        entities.clear();boxes.clear(); World world=plugin.getServer().getWorld(worldId);
        if(world!=null) for(long chunk:chunks) PaperChunkTickets.release(plugin,world,(int)chunk,(int)(chunk>>32));
        chunks.clear();
    }
    public String diagnostics() {
        return "deathboxes="+boxes.size()+" "+boxes.values().stream().map(v->"id="+v.box.id()+" dead="+v.box.deceasedName()+" location="+v.box.location()+" stacks="+Arrays.stream(v.inventory.getContents()).filter(Objects::nonNull).filter(i->!i.getType().isAir()).count()).toList();
    }
    public static final class View implements InventoryHolder {
        public final PaperDeathBoxes owner; public final DeathBox box; public final Inventory inventory;
        boolean valid=true; List<UUID> visuals=List.of();
        View(PaperDeathBoxes owner,DeathBox box) { this.owner=owner;this.box=box;inventory=Bukkit.createInventory(this,54,Component.text("DeathBox: "+box.deceasedName())); }
        @Override public Inventory getInventory() { return inventory; }
    }
}
