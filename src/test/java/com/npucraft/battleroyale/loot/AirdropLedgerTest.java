package com.npucraft.battleroyale.loot;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AirdropLedgerTest {
    @TempDir Path root;
    private static final String FILE="battleroyale-airdrops.ledger", NEXT=FILE+".next";
    private final UUID session=UUID.randomUUID();
    private Path world(String name)throws IOException { return Files.createDirectory(root.resolve(name)); }
    @Test void successfulClaimIsDurableMonotonicAndBoundToOneSession() throws Exception {
        var world=world("world");
        assertTrue(AirdropLedger.claim(world,session,0));
        assertEquals(session+"\n0\n",Files.readString(world.resolve(FILE))); assertFalse(Files.exists(world.resolve(NEXT)));
        assertFalse(AirdropLedger.claim(world,session,0));
        assertTrue(AirdropLedger.claim(world,session,3));
        for(int stage=0;stage<=3;stage++)assertFalse(AirdropLedger.claim(world,session,stage));
        assertThrows(IOException.class,()->AirdropLedger.claim(world,UUID.randomUUID(),4));
        assertEquals(session+"\n3\n",Files.readString(world.resolve(FILE)));
        assertTrue(AirdropLedger.claim(world,session,4));
    }
    @Test void staleUncommittedNextFileCannotConsumeARoundOrCorruptTheLastCommit() throws Exception {
        var world=world("world"); assertTrue(AirdropLedger.claim(world,session,0));
        Files.writeString(world.resolve(NEXT),"interrupted partial record");
        assertFalse(AirdropLedger.claim(world,session,0));
        assertEquals(session+"\n0\n",Files.readString(world.resolve(FILE)));
        assertTrue(AirdropLedger.claim(world,session,1));
        assertEquals(session+"\n1\n",Files.readString(world.resolve(FILE))); assertFalse(Files.exists(world.resolve(NEXT)));
    }
    @Test void failedTemporaryWritePreservesThePreviouslyCommittedClaim() throws Exception {
        var world=world("world"); assertTrue(AirdropLedger.claim(world,session,0));
        Files.createDirectory(world.resolve(NEXT));
        assertThrows(IOException.class,()->AirdropLedger.claim(world,session,1));
        assertEquals(session+"\n0\n",Files.readString(world.resolve(FILE)));
        Files.delete(world.resolve(NEXT));
        assertTrue(AirdropLedger.claim(world,session,1));
    }
    @Test void corruptionOversizeAndMissingWorldFailClosedWithoutOverwritingEvidence() throws Exception {
        String[] corrupt={"",session+"\n-1\n",session+"\nzero\n",session+"\n1",session+"\n1\nextra\n","not-a-uuid\n1\n","x".repeat(257)};
        for(int i=0;i<corrupt.length;i++) {
            var world=world("corrupt"+i); var file=world.resolve(FILE); Files.writeString(file,corrupt[i]);
            assertThrows(IOException.class,()->AirdropLedger.claim(world,session,2));
            assertEquals(corrupt[i],Files.readString(file)); assertFalse(Files.exists(world.resolve(NEXT)));
        }
        assertThrows(IOException.class,()->AirdropLedger.claim(root.resolve("missing"),session,0));
        var world=world("negative"); assertThrows(IOException.class,()->AirdropLedger.claim(world,session,-1));
        assertFalse(Files.exists(world.resolve(FILE)));
    }
    @Test void distinctRuntimeWorldsHaveIndependentLedgers() throws Exception {
        var first=world("first");var second=world("second");var other=UUID.randomUUID();
        assertTrue(AirdropLedger.claim(first,session,0));assertTrue(AirdropLedger.claim(second,other,0));
        assertTrue(AirdropLedger.claim(first,session,2));assertTrue(AirdropLedger.claim(second,other,1));
        assertEquals(other+"\n1\n",Files.readString(second.resolve(FILE)));
    }
    @Test void atomicReplacementWithAnOpenReaderEitherCommitsOrKeepsThePreviousRecord() throws Exception {
        var world=world("world"); AirdropLedger.claim(world,session,0);
        var file=world.resolve(FILE);
        for(int stage=1;stage<=25;stage++) {
            String previous=session+"\n"+(stage-1)+"\n",replacement=session+"\n"+stage+"\n";
            boolean committed;
            // Hold a real read handle across rename: POSIX may replace the directory entry while
            // Windows may deny replacement. Both must preserve the already-open record verbatim.
            try(var reader=Files.newInputStream(file)) {
                String prefix=new String(reader.readNBytes(1),StandardCharsets.UTF_8);
                try { committed=AirdropLedger.claim(world,session,stage); assertTrue(committed); }
                catch(AccessDeniedException denied) {
                    assertTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"),"Only Windows read-sharing refusal is an accepted alternative");
                    assertEquals(world.resolve(NEXT).toString(),denied.getFile());
                    assertEquals(file.toString(),denied.getOtherFile());
                    committed=false;
                }
                assertEquals(previous,prefix+new String(reader.readAllBytes(),StandardCharsets.UTF_8));
                assertEquals(committed?replacement:previous,Files.readString(file));
            }
            if(!committed) {
                // The rejected rename must not consume the round or silently publish .next.
                assertEquals(previous,Files.readString(file));
                assertTrue(AirdropLedger.claim(world,session,stage));
            }
            assertEquals(replacement,Files.readString(file));
            assertFalse(Files.exists(world.resolve(NEXT)));
            assertFalse(AirdropLedger.claim(world,session,stage));
        }
    }
    @Test void linkedLedgerAndTemporaryPathCannotOverwriteAnExternalFile() throws Exception {
        var outside=root.resolve("external");Files.writeString(outside,"keep");
        for(String name:new String[]{FILE,NEXT}) {
            var world=world("linked"+name.length());var link=world.resolve(name);
            try { Files.createSymbolicLink(link,outside); }
            catch(IOException|UnsupportedOperationException|SecurityException unavailable) { assumeTrue(false,"Symbolic links unavailable: "+unavailable.getClass().getSimpleName()); }
            try { assertThrows(IOException.class,()->AirdropLedger.claim(world,session,0));assertEquals("keep",Files.readString(outside)); }
            finally { Files.deleteIfExists(link); }
        }
    }
    @Test void linkedWorldRootIsRejected() throws Exception {
        var actual=world("actual");var link=root.resolve("world-link");
        try { Files.createSymbolicLink(link,actual); }
        catch(IOException|UnsupportedOperationException|SecurityException unavailable) { assumeTrue(false,"Symbolic links unavailable: "+unavailable.getClass().getSimpleName()); }
        try { assertThrows(IOException.class,()->AirdropLedger.claim(link,session,0));assertFalse(Files.exists(actual.resolve(FILE))); }
        finally { Files.deleteIfExists(link); }
    }
}
