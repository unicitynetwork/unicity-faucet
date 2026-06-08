package org.unicitylabs.faucet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.LocalDate;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Unit tests for the token-file retention sweep. These verify the pure pruning
 * logic ({@link FaucetService#pruneOldTokenShards}) without constructing a full
 * FaucetService (whose ctor opens a Nostr connection).
 */
public class TokenRetentionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Create tokens/<name>/ with `fileCount` token files in it. */
    private File shard(File tokensRoot, String name, int fileCount) throws Exception {
        File dir = new File(tokensRoot, name);
        assertTrue(dir.mkdirs());
        for (int i = 0; i < fileCount; i++) {
            assertTrue(new File(dir, "token_" + i + ".json").createNewFile());
        }
        return dir;
    }

    @Test
    public void prunesShardsOlderThanRetentionAndKeepsRecentOnes() throws Exception {
        File tokensRoot = tmp.newFolder("tokens");
        LocalDate today = LocalDate.of(2026, 6, 8);

        File old1 = shard(tokensRoot, "2026-05-01", 3); // 38 days old -> prune
        File old2 = shard(tokensRoot, "2026-05-31", 2); // 8 days old  -> prune (cutoff = Jun 1)
        File edge = shard(tokensRoot, "2026-06-01", 1); // exactly cutoff -> keep
        File recent = shard(tokensRoot, "2026-06-08", 5); // today -> keep

        List<String> pruned = FaucetService.pruneOldTokenShards(tokensRoot, 7, today);

        assertEquals(2, pruned.size());
        assertTrue(pruned.contains("2026-05-01"));
        assertTrue(pruned.contains("2026-05-31"));

        assertFalse("old shard should be deleted", old1.exists());
        assertFalse("old shard should be deleted", old2.exists());
        assertTrue("cutoff-day shard should be kept", edge.exists());
        assertTrue("today's shard should be kept", recent.exists());
    }

    @Test
    public void leavesNonDateNamedEntriesAndLooseFilesUntouched() throws Exception {
        File tokensRoot = tmp.newFolder("tokens");
        LocalDate today = LocalDate.of(2026, 6, 8);

        File oldShard = shard(tokensRoot, "2026-01-01", 1); // ancient -> prune
        File notADate = shard(tokensRoot, "misc", 1);       // not a date -> keep
        File looseFile = new File(tokensRoot, "token_legacy.json"); // pre-shard flat file -> keep
        assertTrue(looseFile.createNewFile());

        List<String> pruned = FaucetService.pruneOldTokenShards(tokensRoot, 7, today);

        assertEquals(1, pruned.size());
        assertFalse(oldShard.exists());
        assertTrue("non-date directory must be left alone", notADate.exists());
        assertTrue("loose file must be left alone", looseFile.exists());
    }

    @Test
    public void retentionZeroDisablesPruning() throws Exception {
        File tokensRoot = tmp.newFolder("tokens");
        LocalDate today = LocalDate.of(2026, 6, 8);

        File ancient = shard(tokensRoot, "2020-01-01", 1);
        File yesterday = shard(tokensRoot, "2026-06-07", 1);

        List<String> pruned = FaucetService.pruneOldTokenShards(tokensRoot, 0, today);

        assertTrue("retention=0 must disable pruning", pruned.isEmpty());
        assertTrue("nothing should be deleted when disabled", ancient.exists());
        assertTrue(yesterday.exists());
    }

    @Test
    public void partiallyPrunedShardIsNotReportedAsPruned() throws Exception {
        File tokensRoot = tmp.newFolder("tokens");
        File old = shard(tokensRoot, "2020-01-01", 1);
        // Block deletion by making the shard dir non-writable (a file can't be
        // removed unless its parent dir is writable). Skip where this isn't
        // honored (e.g. running as root).
        assumeTrue("filesystem honors read-only dir", old.setWritable(false, false));
        try {
            List<String> pruned = FaucetService.pruneOldTokenShards(
                    tokensRoot, 7, LocalDate.of(2026, 6, 8));
            assumeTrue("deletion was actually blocked", old.exists());
            assertTrue("partially-pruned shard must not be reported as pruned", pruned.isEmpty());
        } finally {
            old.setWritable(true, false); // allow TemporaryFolder cleanup
        }
    }

    @Test
    public void missingTokensRootReturnsEmptyAndDoesNotThrow() {
        File tokensRoot = new File(tmp.getRoot(), "does-not-exist");
        List<String> pruned = FaucetService.pruneOldTokenShards(tokensRoot, 7, LocalDate.of(2026, 6, 8));
        assertTrue(pruned.isEmpty());
    }

    @Test
    public void deleteDirRecursivelyRemovesNestedContentAndCountsFiles() throws Exception {
        File dir = tmp.newFolder("shard");
        new File(dir, "a.json").createNewFile();
        new File(dir, "b.json").createNewFile();
        File nested = new File(dir, "nested");
        assertTrue(nested.mkdirs());
        new File(nested, "c.json").createNewFile();

        int removed = FaucetService.deleteDirRecursively(dir);

        assertEquals(3, removed);
        assertFalse(dir.exists());
    }
}
