package com.npucraft.battleroyale.loot;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GroundSupplyLedgerTest {
    @TempDir Path root;
    private final UUID session=UUID.randomUUID(),worldId=UUID.randomUUID();
    private Path world(String name)throws IOException{return Files.createDirectory(root.resolve(name));}
    private GroundSupplyLedger ledger(Path world){return new GroundSupplyLedger(world,session,worldId);}
    private GroundSupplyLedger.Point point(int id){return new GroundSupplyLedger.Point(id,-33+id,81,-17,"basic",Long.MIN_VALUE+id,1,5);}
    private Path file(Path world){return world.resolve(GroundSupplyLedger.FILE);}

    @Test void missingPlanMeansLegacyAndClaimNeverCreatesOne()throws Exception{
        var world=world("legacy");var ledger=ledger(world);
        assertTrue(ledger.read().isEmpty());assertThrows(IOException.class,()->ledger.claim(0));
        assertFalse(Files.exists(file(world)));
        ledger.seal(List.of());var saved=ledger.read().orElseThrow();
        assertTrue(saved.points().isEmpty());assertTrue(saved.claimed().isEmpty());assertThrows(IOException.class,()->ledger.claim(0));
    }
    @Test void outOfOrderClaimsSurviveNewInstancesWithoutBurningEarlierPoints()throws Exception{
        var world=world("world");var first=ledger(world);
        first.seal(List.of(point(2),point(0),point(1)));
        assertEquals(List.of(point(0),point(1),point(2)),first.read().orElseThrow().points());
        assertTrue(first.claim(2));assertEquals(Set.of(2),ledger(world).read().orElseThrow().claimed());
        assertTrue(ledger(world).claim(0));assertFalse(first.claim(2));
        String committed=Files.readString(file(world));assertFalse(ledger(world).claim(0));assertEquals(committed,Files.readString(file(world)));
        assertTrue(first.claim(1));assertEquals(Set.of(0,1,2),ledger(world).read().orElseThrow().claimed());
    }
    @Test void signedSeedsUnicodeTableAndSnapshotsRoundTripExactly()throws Exception{
        var world=world("metadata");var input=new ArrayList<>(List.of(
                new GroundSupplyLedger.Point(0,-30_000_000,-4096,30_000_000,"补给 <literal>",Long.MIN_VALUE,0,128),
                new GroundSupplyLedger.Point(1,30_000_000,4096,-30_000_000,"custom.table",Long.MAX_VALUE,1,1)));
        var ledger=ledger(world);ledger.seal(input);var before=ledger.read().orElseThrow();input.clear();
        assertEquals(Long.MIN_VALUE,before.points().get(0).seed());assertEquals(Long.MAX_VALUE,before.points().get(1).seed());
        assertEquals("补给 <literal>",before.points().get(0).table());assertThrows(UnsupportedOperationException.class,()->before.points().clear());
        assertThrows(UnsupportedOperationException.class,()->before.claimed().add(0));
        ledger.claim(1);assertTrue(before.claimed().isEmpty());assertEquals(Set.of(1),ledger.read().orElseThrow().claimed());
    }
    @Test void resealCannotEraseClaimsOrOverwriteForeignOrCorruptEvidence()throws Exception{
        var world=world("world");var ledger=ledger(world);ledger.seal(List.of(point(0)));ledger.claim(0);
        String committed=Files.readString(file(world));assertThrows(IOException.class,()->ledger.seal(List.of(point(0))));assertEquals(committed,Files.readString(file(world)));
        assertThrows(IOException.class,()->new GroundSupplyLedger(world,UUID.randomUUID(),worldId).seal(List.of()));
        Files.writeString(file(world),"broken");assertThrows(IOException.class,()->ledger.seal(List.of()));assertEquals("broken",Files.readString(file(world)));
    }
    @Test void foreignSessionAndWorldCannotReadOrClaim()throws Exception{
        var world=world("world");ledger(world).seal(List.of(point(0)));String before=Files.readString(file(world));
        for(var foreign:List.of(new GroundSupplyLedger(world,UUID.randomUUID(),worldId),new GroundSupplyLedger(world,session,UUID.randomUUID()))){
            assertThrows(IOException.class,foreign::read);assertThrows(IOException.class,()->foreign.claim(0));assertEquals(before,Files.readString(file(world)));
        }
    }
    @Test void idBudgetAndUnknownClaimsFailWithoutWrites()throws Exception{
        var world=world("world");var ledger=ledger(world);ledger.seal(List.of(point(0)));String before=Files.readString(file(world));
        for(int id:new int[]{-1,1,GroundSupplyLedger.MAX_POINTS,Integer.MAX_VALUE})assertThrows(IOException.class,()->ledger.claim(id));
        assertEquals(before,Files.readString(file(world)));
        assertThrows(IllegalArgumentException.class,()->point(-1));
        assertThrows(IllegalArgumentException.class,()->point(GroundSupplyLedger.MAX_POINTS));
    }
    @Test void maximumPlanIsBoundedAndClaimableAtBothEnds()throws Exception{
        var world=world("max");var ledger=ledger(world);ledger.seal(IntStream.range(0,GroundSupplyLedger.MAX_POINTS).mapToObj(this::point).toList());
        assertTrue(Files.size(file(world))<=GroundSupplyLedger.MAX_BYTES);
        assertTrue(ledger.claim(GroundSupplyLedger.MAX_POINTS-1));assertTrue(ledger.claim(0));
        assertEquals(Set.of(0,GroundSupplyLedger.MAX_POINTS-1),ledger(world).read().orElseThrow().claimed());
        var tooMany=new ArrayList<>(IntStream.range(0,GroundSupplyLedger.MAX_POINTS).mapToObj(this::point).toList());tooMany.add(point(0));
        var other=world("too-many");assertThrows(IOException.class,()->ledger(other).seal(tooMany));assertFalse(Files.exists(file(other)));
    }
    @Test void gapsDuplicatesAndInvalidSnapshotsCannotBeSealed()throws Exception{
        for(var values:List.of(List.of(point(1)),List.of(point(0),point(2)),List.of(point(0),point(0)))){
            var world=world(UUID.randomUUID().toString());assertThrows(IOException.class,()->ledger(world).seal(values));assertFalse(Files.exists(file(world)));
        }
        assertThrows(IllegalArgumentException.class,()->new GroundSupplyLedger.Snapshot(List.of(point(0)),Set.of(1)));
    }
    @Test void pointFieldsRejectInvalidCoordinatesTablesAndRolls(){
        assertThrows(IllegalArgumentException.class,()->new GroundSupplyLedger.Point(0,Integer.MIN_VALUE,1,0,"basic",0,1,1));
        assertThrows(IllegalArgumentException.class,()->new GroundSupplyLedger.Point(0,0,4097,0,"basic",0,1,1));
        assertThrows(IllegalArgumentException.class,()->new GroundSupplyLedger.Point(0,0,1,0," ",0,1,1));
        assertThrows(IllegalArgumentException.class,()->new GroundSupplyLedger.Point(0,0,1,0,"bad\nname",0,1,1));
        assertThrows(IllegalArgumentException.class,()->new GroundSupplyLedger.Point(0,0,1,0,"x".repeat(129),0,1,1));
        assertThrows(IllegalArgumentException.class,()->new GroundSupplyLedger.Point(0,0,1,0,"basic",0,-1,1));
        assertThrows(IllegalArgumentException.class,()->new GroundSupplyLedger.Point(0,0,1,0,"basic",0,2,1));
        assertThrows(IllegalArgumentException.class,()->new GroundSupplyLedger.Point(0,0,1,0,"basic",0,1,129));
    }
    @Test void strictJsonRejectsTruncationTrailingDuplicateAndTypeConfusion()throws Exception{
        var world=world("strict");var ledger=ledger(world);ledger.seal(List.of(point(0)));String valid=Files.readString(file(world));
        var corrupt=List.of("",valid.substring(0,valid.length()/2),valid+"{}",valid.replace("\"version\":1","\"version\":1,\"version\":1"),
                valid.replace("\"id\":0","\"id\":0,\"id\":0"),valid.replace("\"version\":1","\"version\":\"1\""),
                valid.replace("\"minRolls\":1","\"minRolls\":1.0"),valid.replace("\"seed\":"+Long.MIN_VALUE,"\"seed\":-9223372036854775809"),
                valid.replace("\"claimed\":[]","\"claimed\":[0,0]"),valid.replace("\"claimed\":[]","\"claimed\":[\"0\"]"));
        for(String value:corrupt){Files.writeString(file(world),value);assertThrows(IOException.class,ledger::read);assertThrows(IOException.class,()->ledger.claim(0));assertEquals(value,Files.readString(file(world)));}
    }
    @Test void structurallyValidTamperingAndUnexpectedFieldsFailClosed()throws Exception{
        var world=world("tamper");var ledger=ledger(world);ledger.seal(List.of(point(0)));String valid=Files.readString(file(world));
        List<Consumer<JsonObject>> mutations=List.of(
                value->value.addProperty("schema","other"),value->value.addProperty("version",2),
                value->value.addProperty("unexpected",true),value->value.remove("claimed"),
                value->value.getAsJsonArray("claimed").add(1),value->value.getAsJsonArray("points").get(0).getAsJsonObject().addProperty("x",-32),
                value->value.getAsJsonArray("points").get(0).getAsJsonObject().addProperty("extra",1),
                value->value.addProperty("sha256","0".repeat(64)));
        for(var mutation:mutations){var object=JsonParser.parseString(valid).getAsJsonObject();mutation.accept(object);String changed=object.toString();Files.writeString(file(world),changed);
            assertThrows(IOException.class,ledger::read);assertThrows(IOException.class,()->ledger.claim(0));assertEquals(changed,Files.readString(file(world)));}
    }
    @Test void oversizeInvalidUtf8AndNonFileAreRejected()throws Exception{
        var world=world("oversize");var ledger=ledger(world);Files.write(file(world),new byte[GroundSupplyLedger.MAX_BYTES+1]);
        assertThrows(IOException.class,ledger::read);assertEquals(GroundSupplyLedger.MAX_BYTES+1,Files.size(file(world)));
        Files.write(file(world),new byte[]{(byte)0xc3,(byte)0x28});assertThrows(IOException.class,ledger::read);
        Files.delete(file(world));Files.createDirectory(file(world));assertThrows(IOException.class,ledger::read);assertThrows(IOException.class,()->ledger.seal(List.of()));
        assertThrows(IOException.class,()->ledger(root.resolve("missing")).read());assertThrows(IOException.class,()->ledger(root.resolve("missing")).seal(List.of()));
    }
    @Test void appendContinuesThePlanAndPreservesClaims()throws Exception{
        var world=world("append");var ledger=ledger(world);
        ledger.seal(List.of(point(0)));assertTrue(ledger.claim(0));
        ledger.append(List.of(point(1),point(2)));
        var saved=ledger.read().orElseThrow();
        assertEquals(List.of(point(0),point(1),point(2)),saved.points());
        assertEquals(Set.of(0),saved.claimed());
        assertFalse(ledger.claim(0));assertTrue(ledger.claim(2));
        var second=ledger(world);second.append(List.of(point(3)));
        var finalState=second.read().orElseThrow();
        assertEquals(4,finalState.points().size());assertEquals(Set.of(0,2),finalState.claimed());
    }
    @Test void appendRequiresACommittedContinuousPlan()throws Exception{
        var world=world("append-guard");var ledger=ledger(world);
        assertThrows(IOException.class,()->ledger.append(List.of(point(0))));assertFalse(Files.exists(file(world)));
        ledger.seal(List.of(point(0)));String before=Files.readString(file(world));
        assertThrows(IOException.class,()->ledger.append(List.of(point(2))));
        assertThrows(IOException.class,()->ledger.append(List.of(point(0))));
        assertEquals(before,Files.readString(file(world)));
    }
    @Test void appendCannotExceedThePointBudget()throws Exception{
        var world=world("append-max");var ledger=ledger(world);
        ledger.seal(IntStream.range(0,GroundSupplyLedger.MAX_POINTS).mapToObj(this::point).toList());
        assertThrows(IOException.class,()->ledger.append(List.of(point(0))));
    }
    @Test void uncommittedTemporaryFileIsNeverAPlanOrClaim()throws Exception{
        var world=world("temporary");var ledger=ledger(world);Path temporary=world.resolve(GroundSupplyLedger.FILE+".tmp-"+UUID.randomUUID());
        Files.writeString(temporary,"uncommitted");assertTrue(ledger.read().isEmpty());assertThrows(IOException.class,()->ledger.claim(0));
        ledger.seal(List.of(point(0)));assertTrue(ledger.claim(0));assertEquals("uncommitted",Files.readString(temporary));
    }
    @Test void replacementWithOpenReaderEitherCommitsOrPreservesOldRecord()throws Exception{
        var world=world("reader");var ledger=ledger(world);ledger.seal(List.of(point(0)));String old=Files.readString(file(world));boolean committed;
        try(var input=Files.newInputStream(file(world))){
            String prefix=new String(input.readNBytes(1),StandardCharsets.UTF_8);
            try{committed=ledger.claim(0);assertTrue(committed);}
            catch(AccessDeniedException denied){assertTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));committed=false;}
            assertEquals(old,prefix+new String(input.readAllBytes(),StandardCharsets.UTF_8));
            assertEquals(committed?Set.of(0):Set.of(),ledger.read().orElseThrow().claimed());
        }
        if(!committed){assertEquals(old,Files.readString(file(world)));assertTrue(ledger.claim(0));}
        assertEquals(Set.of(0),ledger.read().orElseThrow().claimed());
        try(var files=Files.list(world)){assertEquals(List.of(GroundSupplyLedger.FILE),files.map(path->path.getFileName().toString()).toList());}
    }
    @Test void linkedLedgerDoesNotReadOrOverwriteExternalEvidence()throws Exception{
        var world=world("linked");Path external=root.resolve("external.json");Files.writeString(external,"keep");
        try{Files.createSymbolicLink(file(world),external);}
        catch(IOException|UnsupportedOperationException|SecurityException unavailable){assumeTrue(false,"Symbolic links unavailable");}
        try{assertThrows(IOException.class,()->ledger(world).read());assertThrows(IOException.class,()->ledger(world).seal(List.of()));assertThrows(IOException.class,()->ledger(world).claim(0));assertEquals("keep",Files.readString(external));}
        finally{Files.deleteIfExists(file(world));}
    }
    @Test void linkedWorldCannotPublishIntoAnotherDirectory()throws Exception{
        var real=world("real");var link=root.resolve("linked-world");
        try{Files.createSymbolicLink(link,real);}
        catch(IOException|UnsupportedOperationException|SecurityException unavailable){assumeTrue(false,"Symbolic links unavailable");}
        try{assertThrows(IOException.class,()->ledger(link).seal(List.of(point(0))));assertFalse(Files.exists(file(real)));}
        finally{Files.deleteIfExists(link);}
    }
}