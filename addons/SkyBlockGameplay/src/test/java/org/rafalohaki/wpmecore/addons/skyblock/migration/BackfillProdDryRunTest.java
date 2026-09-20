package org.rafalohaki.wpmecore.addons.skyblock.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1-B dry-run na kopii danych produkcyjnych.
 * Wymaga: prod-snapshot.db w katalogu podanym przez system property "m1b.snapshot"
 * (ścieżka do kopii bazy produkcyjnej). Bez niej test się pomija — dry-run
 * wykonujemy tylko na prawdziwych danych, nie na fiksturze.
 */
class BackfillProdDryRunTest {



    @TempDir
    Path tmp;

    @Test
    void prodSnapshotChecksumsPreservedAndIdempotent() throws Exception {
        String snap = System.getProperty("m1b.snapshot");
        if (snap == null || !Files.exists(Path.of(snap))) {
            System.out.println("SKIP: brak prod-snapshot (podaj -Dm1b.snapshot=/path/prod-snapshot.db)");
            return;
        }

        // Pracujemy na kopii — oryginał snapshotu nietknięty.
        Path work = tmp.resolve("prod-work.db");
        Files.copy(Path.of(snap), work, StandardCopyOption.REPLACE_EXISTING);

        var sql = new SingleConnectionSqlService("jdbc:sqlite:" + work);
        var svc = new BackfillService(sql);

            // 1) Dry-run: checksumy przed, bez mutacji
            var report = svc.dryRun().join();
            System.out.println("[DRY-RUN] checksumBefore=" + report.checksumBefore());
            System.out.println("[DRY-RUN] multipleMembershipPlayers=" + report.multipleMembershipPlayers().size());
            System.out.println("[DRY-RUN] missingOwners=" + report.missingOwners().size());
            System.out.println("[DRY-RUN] noProfileAccounts=" + report.accountsWithoutProfile().size());
            System.out.println("[DRY-RUN] disabledOrLocked=" + report.disabledOrLockedIslands().size());
            assertEquals(report.checksumBefore(), report.checksumAfter(),
                    "dry-run nie może zmienić checksumy");

            // 2) Execute: backfill właściwy
            var exec = svc.backfill(false).join();
            System.out.println("[EXEC] before=" + exec.checksumBefore()
                    + " afterProfile=" + exec.checksumAfter()
                    + " migrated=" + exec.accountsMigrated()
                    + " quarantined=" + exec.ambiguousAccounts()
                    + " sumsMatch=" + exec.checksumMatches());
            assertTrue(exec.checksumMatches(),
                    "suma sald po backfillem musi się zgadzać (before minus kwarantanna)");

            // 3) Idempotencja: drugi przebieg nie zmienia stanu
            long afterFirst = exec.checksumAfter();
            var second = svc.backfill(false).join();
            assertEquals(afterFirst, second.checksumAfter(),
                    "drugi backfill musi być idempotentny");
            assertEquals(0, second.accountsMigrated(),
                    "drugi backfill nie może migrować ponownie");
        sql.shutdown();
    }
}
