package com.npucraft.lastsector.loadout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LoadoutTest {
    StoredItem item=new StoredItem("paper-native",1,"AQID");
    @Test void immutableDefinitionAndIndependentDraft() {
        var slots=new HashMap<Integer,StoredItem>(); slots.put(0,item);
        var definition=new LoadoutDefinition("shared",slots,0); slots.clear();
        EditorDraft draft=new EditorDraft(definition); draft.brush(item); draft.click(36,false); draft.click(0,true);
        assertEquals(item,definition.slots().get(0)); assertFalse(definition.slots().containsKey(39));
        assertEquals(item,draft.snapshot().slots().get(39)); assertFalse(draft.snapshot().slots().containsKey(0));
        assertThrows(UnsupportedOperationException.class,()->definition.slots().clear());
    }
    @ParameterizedTest @ValueSource(ints={-1,41,42,45,49,53,54,100}) void controlSlotsCannotPersist(int slot) {
        EditorDraft draft=new EditorDraft(new LoadoutDefinition("x",Map.of(),0)); draft.brush(item); draft.click(slot,false);
        assertTrue(draft.snapshot().slots().isEmpty());
    }
    @Test void armorMappingAndOffhandExplicit() {
        assertEquals(39,LoadoutSlotMapping.inventorySlot(36)); assertEquals(38,LoadoutSlotMapping.inventorySlot(37));
        assertEquals(37,LoadoutSlotMapping.inventorySlot(38)); assertEquals(36,LoadoutSlotMapping.inventorySlot(39)); assertEquals(40,LoadoutSlotMapping.inventorySlot(40));
    }
    @Test void invalidFormatsAndSlotsFail() {
        assertThrows(IllegalArgumentException.class,()->new StoredItem("x",1,"AQID"));
        assertThrows(IllegalArgumentException.class,()->new StoredItem("paper-native",2,"AQID"));
        assertThrows(IllegalArgumentException.class,()->new StoredItem("paper-native",1,"%%%"));
        assertThrows(IllegalArgumentException.class,()->new LoadoutDefinition("x",Map.of(41,item),0));
        assertThrows(IllegalArgumentException.class,()->new LoadoutDefinition("x",Map.of(),9));
    }
    @Test void atomicReplacementAndFailedWritePreserveOld(@TempDir Path directory) throws Exception {
        Path file=directory.resolve("loadouts.yml"); Files.writeString(file,"old"); AtomicFile.replace(file,"new"); assertEquals("new",Files.readString(file));
        Path invalid=directory.resolve("occupied"); Files.createDirectory(invalid); Files.writeString(invalid.resolve("original"),"old");
        assertThrows(java.io.IOException.class,()->AtomicFile.replace(invalid,"bad")); assertEquals("old",Files.readString(invalid.resolve("original")));
        try(var paths=Files.list(directory)) { assertEquals(2,paths.count()); }
    }
}
