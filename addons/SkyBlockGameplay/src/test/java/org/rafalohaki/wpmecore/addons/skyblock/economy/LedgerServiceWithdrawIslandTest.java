package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;

import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerServiceWithdrawIslandTest {

    private SingleConnectionSqlService sql;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void withdrawIslandDebitsAtomically() {
        LedgerService ledger = new LedgerService(new LedgerDao(sql), 0L);
        UUID island = UUID.randomUUID();
        ledger.depositIsland(island, 500L, "tx:dep", "test").join();

        LedgerDao.Mutation result = ledger.withdrawIsland(island, 300L, "tx:wd", "minion_upgrade").join();

        assertTrue(result.applied());
        assertFalse(result.insufficient());
        assertEquals(200L, ledger.authoritativeIslandBalance(island).join());
    }

    @Test
    void withdrawIslandRejectsWhenBalanceTooLow() {
        LedgerService ledger = new LedgerService(new LedgerDao(sql), 0L);
        UUID island = UUID.randomUUID();
        ledger.depositIsland(island, 100L, "tx:dep", "test").join();

        LedgerDao.Mutation result = ledger.withdrawIsland(island, 300L, "tx:wd", "minion_upgrade").join();

        assertFalse(result.applied());
        assertTrue(result.insufficient());
        assertEquals(100L, ledger.authoritativeIslandBalance(island).join());
    }
}
