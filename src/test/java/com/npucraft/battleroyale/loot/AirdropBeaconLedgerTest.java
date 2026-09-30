package com.npucraft.battleroyale.loot;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AirdropBeaconLedgerTest {
    @TempDir Path world;
    private final UUID session=UUID.randomUUID(),dimension=UUID.randomUUID();
    private AirdropBeaconLedger.Plan plan(int stage){
        var cells=new ArrayList<AirdropBeaconLedger.Cell>();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)cells.add(new AirdropBeaconLedger.Cell(10+dx,79,20+dz,"minecraft:stone","minecraft:iron_block"));
        cells.add(new AirdropBeaconLedger.Cell(10,80,20,"minecraft:air","minecraft:beacon"));
        cells.add(new AirdropBeaconLedger.Cell(10,81,20,"minecraft:air","minecraft:yellow_stained_glass"));
        return new AirdropBeaconLedger.Plan(session,dimension,stage,10,80,20,cells);
    }
    @Test void exactBlockStatesRoundTripAndMultipleRoundsRemainBoundToSessionAndDimension()throws Exception {
        var first=plan(0);var second=plan(7);AirdropBeaconLedger.save(world,first);AirdropBeaconLedger.save(world,second);
        assertEquals(Set.of(first,second),new HashSet<>(AirdropBeaconLedger.load(world,session,dimension)));
        assertThrows(IOException.class,()->AirdropBeaconLedger.load(world,UUID.randomUUID(),dimension));
        assertThrows(IOException.class,()->AirdropBeaconLedger.load(world,session,UUID.randomUUID()));
    }
    @Test void cleanupNeedsBothChunkOwnershipAndUnchangedPlacedState(){
        var base=plan(0).cells().getFirst();
        assertTrue(base.restores("minecraft:iron_block",true));
        assertFalse(base.restores("minecraft:diamond_block",true),"An external edit is preserved");
        assertFalse(base.restores("minecraft:iron_block",false),"A cleared chunk marker prevents stale-ledger replay over later player blocks");
        assertFalse(base.restores("minecraft:stone",true),"A partial pre-mutation intention does not rewrite untouched blocks");
    }
    @Test void interruptedTemporaryRecordNeverAuthorizesMutation()throws Exception {
        var dir=Files.createDirectory(world.resolve(AirdropBeaconLedger.DIRECTORY));
        Files.writeString(dir.resolve("beacon-0.dat.tmp-"+UUID.randomUUID()),"partial");
        assertTrue(AirdropBeaconLedger.load(world,session,dimension).isEmpty());
        AirdropBeaconLedger.save(world,plan(0));assertEquals(List.of(plan(0)),AirdropBeaconLedger.load(world,session,dimension));
    }
    @Test void corruptionOrOversizeRejectsWithoutChangingTheEvidence()throws Exception {
        AirdropBeaconLedger.save(world,plan(0));var file=world.resolve(AirdropBeaconLedger.DIRECTORY).resolve("beacon-0.dat");
        Files.writeString(file,"broken");assertThrows(IOException.class,()->AirdropBeaconLedger.load(world,session,dimension));assertEquals("broken",Files.readString(file));
        Files.write(file,new byte[32769]);assertThrows(IOException.class,()->AirdropBeaconLedger.load(world,session,dimension));
    }
    @Test void failedAtomicWritePreservesTheCommittedPlan()throws Exception {
        var plan=plan(0);AirdropBeaconLedger.save(world,plan);
        var dir=world.resolve(AirdropBeaconLedger.DIRECTORY);Files.createDirectory(dir.resolve("beacon-1.dat"));
        assertThrows(IOException.class,()->AirdropBeaconLedger.save(world,plan(1)));
        Files.delete(dir.resolve("beacon-1.dat"));assertEquals(List.of(plan),AirdropBeaconLedger.load(world,session,dimension));
    }
    @Test void arbitraryShapeDuplicateCellAndUnexpectedMaterialsCannotBecomeRestorePlans(){
        var original=plan(0);var cells=new ArrayList<>(original.cells());cells.set(0,cells.get(1));
        assertThrows(IllegalArgumentException.class,()->new AirdropBeaconLedger.Plan(session,dimension,0,10,80,20,cells));
        cells.set(0,new AirdropBeaconLedger.Cell(9,79,19,"minecraft:stone","minecraft:diamond_block"));
        assertThrows(IllegalArgumentException.class,()->new AirdropBeaconLedger.Plan(session,dimension,0,10,80,20,cells));
        cells.set(0,new AirdropBeaconLedger.Cell(500,79,19,"minecraft:stone","minecraft:iron_block"));
        assertThrows(IllegalArgumentException.class,()->new AirdropBeaconLedger.Plan(session,dimension,0,10,80,20,cells));
        assertThrows(IllegalArgumentException.class,()->plan(128));
    }
    @Test void linkedLedgerDirectoryRefusedWithoutTouchingTarget()throws Exception {
        var outside=Files.createTempDirectory(world.getParent(),"beacon-link-target");
        try{Files.createSymbolicLink(world.resolve(AirdropBeaconLedger.DIRECTORY),outside);}
        catch(UnsupportedOperationException|FileSystemException noPrivilege){assumeTrue(false,"Symbolic-link creation unavailable");}
        assertThrows(IOException.class,()->AirdropBeaconLedger.save(world,plan(0)));
        assertThrows(IOException.class,()->AirdropBeaconLedger.load(world,session,dimension));
        try(var files=Files.list(outside)){assertEquals(0,files.count());}
    }
}
