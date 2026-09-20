package org.rafalohaki.wpmecore.addons.skyblock.admin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.analytics.OnboardingAnalyticsService;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.IslandProfileDao;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.ProfileStateService;
import org.rafalohaki.wpmecore.addons.skyblock.migration.BackfillReport;
import org.rafalohaki.wpmecore.addons.skyblock.migration.BackfillService;
import org.rafalohaki.wpmecore.addons.skyblock.playtime.ProfilePlaytimeDao;
import org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileRecoveryService;
import org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileSnapshotDao;
import org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileTransitionDao;
import org.rafalohaki.wpmecore.api.util.AddonBootstrap;
import org.rafalohaki.wpmecore.api.util.CompactDurationFormatter;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * M1-D: admin view of profile and operations.
 * <p>
 * Commands:
 * - /skyblock admin profile view <player>
 * - /skyblock admin profile operations <player|profileId> [limit]
 * - /skyblock admin recovery status
 * - /skyblock admin recovery resume <operationId>
 * - /skyblock admin recovery quarantine <operationId> <reason>
 * - /skyblock admin backfill dry-run
 * - /skyblock admin backfill execute
 * - /skyblock admin playtime <player>
 * - /skyblock admin analytics [player]
 */
public final class SkyBlockAdminCommands {

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final MiniMessage mm;
    private final IslandProfileDao islandProfileDao;
    private final ProfileStateService profileStateService;
    private final ProfileTransitionDao transitionDao;
    private final ProfileSnapshotDao snapshotDao;
    private final ProfileRecoveryService recoveryService;
    private final ProfilePlaytimeDao playtimeDao;
    private final OnboardingAnalyticsService analytics;
    private final BackfillService backfillService;
    private final LedgerDao ledgerDao;

    public SkyBlockAdminCommands(
            @NotNull org.bukkit.plugin.java.JavaPlugin plugin,
            @NotNull MiniMessage mm,
            @NotNull IslandProfileDao islandProfileDao,
            @NotNull ProfileStateService profileStateService,
            @NotNull ProfileTransitionDao transitionDao,
            @NotNull ProfileSnapshotDao snapshotDao,
            @NotNull ProfileRecoveryService recoveryService,
            @NotNull ProfilePlaytimeDao playtimeDao,
            @NotNull OnboardingAnalyticsService analytics,
            @NotNull BackfillService backfillService,
            @NotNull LedgerDao ledgerDao) {
        this.plugin = plugin;
        this.mm = mm;
        this.islandProfileDao = islandProfileDao;
        this.profileStateService = profileStateService;
        this.transitionDao = transitionDao;
        this.snapshotDao = snapshotDao;
        this.recoveryService = recoveryService;
        this.playtimeDao = playtimeDao;
        this.analytics = analytics;
        this.backfillService = backfillService;
        this.ledgerDao = ledgerDao;
    }

    public void register(@NotNull io.papermc.paper.command.brigadier.Commands registrar) {
        // /skyblock admin profile view <player>
        registrar.register(Commands.literal("skyblock")
                .then(Commands.literal("admin")
                        .requires(s -> s.getSender().hasPermission("skyblockgameplay.admin"))
                        .then(Commands.literal("profile")
                                .then(Commands.literal("view")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> viewProfile(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player")))))
                                .then(Commands.literal("operations")
                                        .then(Commands.argument("target", StringArgumentType.word())
                                                .executes(ctx -> viewOperations(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "target"), 10))
                                                .then(Commands.argument("limit", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 50))
                                                        .executes(ctx -> viewOperations(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "target"), ctx.getArgument("limit", Integer.class))))))
                        )
                        .then(Commands.literal("recovery")
                                .then(Commands.literal("status")
                                        .executes(ctx -> recoveryStatus(ctx.getSource().getSender())))
                                .then(Commands.literal("resume")
                                        .then(Commands.argument("operationId", StringArgumentType.greedyString())
                                                .executes(ctx -> recoveryResume(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "operationId")))))
                                .then(Commands.literal("quarantine")
                                        .then(Commands.argument("operationId", StringArgumentType.word())
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(ctx -> recoveryQuarantine(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "operationId"), StringArgumentType.getString(ctx, "reason"))))))
                        )
                        .then(Commands.literal("backfill")
                                .then(Commands.literal("dry-run")
                                        .executes(ctx -> backfillDryRun(ctx.getSource().getSender())))
                                .then(Commands.literal("execute")
                                        .executes(ctx -> backfillExecute(ctx.getSource().getSender()))))
                        .then(Commands.literal("playtime")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> viewPlaytime(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("analytics")
                                .executes(ctx -> viewAnalytics(ctx.getSource().getSender(), null))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> viewAnalytics(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("snapshot")
                                .then(Commands.literal("verify")
                                        .then(Commands.argument("snapshotId", StringArgumentType.greedyString())
                                                .executes(ctx -> verifySnapshot(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "snapshotId"))))))
                ).build(), "SkyBlock admin panel — M1-D recovery & profile view", List.of("sbadmin"));
    }

    private int viewProfile(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayerExact(playerName);
        UUID uuid = target != null ? target.getUniqueId() : null;
        if (uuid == null) {
            try {
                uuid = Bukkit.getOfflinePlayer(playerName).getUniqueId();
            } catch (Exception e) {
                sender.sendMessage(Component.text("Gracz nie znaleziony: " + playerName, NamedTextColor.RED));
                return 0;
            }
        }
        final UUID fUuid = uuid;
        profileStateService.activeMembership(fUuid).whenComplete((opt, ex) -> {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                if (ex != null) {
                    sender.sendMessage(Component.text("Błąd odczytu profilu: " + ex.getMessage(), NamedTextColor.RED));
                    return;
                }
                if (opt.isEmpty()) {
                    sender.sendMessage(Component.text("Brak aktywnego profilu dla " + playerName + " (" + fUuid + ")", NamedTextColor.YELLOW));
                    return;
                }
                var m = opt.get();
                sender.sendMessage(mm.deserialize("<green>Profil dla <white>" + playerName + "</white>:</green>"));
                sender.sendMessage(Component.text("  islandId: " + m.islandId(), NamedTextColor.GRAY));
                sender.sendMessage(Component.text("  playerUuid: " + m.playerUuid(), NamedTextColor.GRAY));
                sender.sendMessage(Component.text("  role: " + m.role() + " status: " + m.status(), NamedTextColor.GRAY));
                sender.sendMessage(Component.text("  joinedAt: " + m.joinedAt() + " updatedAt: " + m.updatedAt(), NamedTextColor.GRAY));
                // Also show island profile
                islandProfileDao.findProfile(m.islandId()).whenComplete((profOpt, ex2) -> {
                    Bukkit.getGlobalRegionScheduler().run(plugin, t2 -> {
                        if (profOpt != null && profOpt.isPresent()) {
                            var p = profOpt.get();
                            sender.sendMessage(Component.text("  island mode: " + p.mode() + " status: " + p.status(), NamedTextColor.GRAY));
                        }
                    });
                });
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int viewOperations(CommandSender sender, String target, int limit) {
        UUID uuid = null;
        try {
            uuid = UUID.fromString(target);
        } catch (IllegalArgumentException ignored) {
            Player p = Bukkit.getPlayerExact(target);
            if (p != null) uuid = p.getUniqueId();
            else try { uuid = Bukkit.getOfflinePlayer(target).getUniqueId(); } catch (Exception e) { }
        }
        if (uuid == null) {
            sender.sendMessage(Component.text("Nieprawidłowy target: " + target, NamedTextColor.RED));
            return 0;
        }
        // Try as profileId first, then as playerUuid
        final UUID fUuid = uuid;
        // Check if it's a profile
        islandProfileDao.findProfile(fUuid).whenComplete((profOpt, ex) -> {
            if (profOpt != null && profOpt.isPresent()) {
                transitionDao.listByProfile(fUuid, limit).whenComplete((list, ex2) -> {
                    Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                        sender.sendMessage(mm.deserialize("<green>Operacje dla profilu <white>" + fUuid + "</white> (limit " + limit + "):</green>"));
                        if (list.isEmpty()) sender.sendMessage(Component.text("  (brak operacji)", NamedTextColor.GRAY));
                        for (var op : list) {
                            sender.sendMessage(Component.text("  " + op.operationId() + " [" + op.transitionType() + "] " + op.fromStatus() + "->" + op.toStatus() + " checkpoint=" + op.checkpoint() + " result=" + op.result(), NamedTextColor.GRAY));
                        }
                    });
                });
            } else {
                transitionDao.listPending(50).whenComplete((pending, ex2) -> {
                    Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                        sender.sendMessage(mm.deserialize("<green>Pending operacje (filtr po player <white>" + fUuid + "</white>):</green>"));
                        long count = pending.stream().filter(o -> o.playerUuid().equals(fUuid)).count();
                        pending.stream().filter(o -> o.playerUuid().equals(fUuid)).limit(limit).forEach(op -> sender.sendMessage(Component.text("  " + op.operationId() + " [" + op.transitionType() + "] " + op.checkpoint() + " result=" + op.result(), NamedTextColor.GRAY)));
                        if (count == 0) sender.sendMessage(Component.text("  (brak pending dla tego gracza)", NamedTextColor.GRAY));
                    });
                });
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int recoveryStatus(CommandSender sender) {
        transitionDao.listPending(100).whenComplete((list, ex) -> {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                if (ex != null) {
                    sender.sendMessage(Component.text("Błąd: " + ex.getMessage(), NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(mm.deserialize("<green>Recovery status: <white>" + list.size() + "</white> pending operacji (non-terminal)</green>"));
                for (var op : list.subList(0, Math.min(20, list.size()))) {
                    sender.sendMessage(Component.text("  " + op.operationId() + " " + op.transitionType() + " " + op.checkpoint() + " profile=" + op.profileId(), NamedTextColor.GRAY));
                }
                if (list.size() > 20) sender.sendMessage(Component.text("  ... +" + (list.size() - 20) + " więcej (zobacz /skyblock admin profile operations <id>)", NamedTextColor.GRAY));
            });
        });
        // Also show playtime reconciliation info
        recoveryService.recoverAll().whenComplete((report, ex2) -> {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                if (report != null) {
                    sender.sendMessage(Component.text("  playtime reconciled: " + report.playtimeReconciled() + " resumed: " + report.resumed() + " quarantined: " + report.quarantined(), NamedTextColor.GRAY));
                }
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int recoveryResume(CommandSender sender, String operationId) {
        String op = operationId.trim();
        recoveryService.resume(op).whenComplete((res, ex) -> {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                if (ex != null) {
                    sender.sendMessage(Component.text("Resume błąd: " + ex.getMessage(), NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(mm.deserialize("<green>Resume <white>" + op + "</white>: <yellow>" + res.kind() + "</yellow> " + res.message() + "</green>"));
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int recoveryQuarantine(CommandSender sender, String operationId, String reason) {
        transitionDao.quarantine(operationId, reason).whenComplete((ok, ex) -> {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                if (ex != null) {
                    sender.sendMessage(Component.text("Quarantine błąd: " + ex.getMessage(), NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text("Quarantine " + operationId + ": " + (ok ? "OK (" + reason + ")" : "FAILED (not pending?)"), ok ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int backfillDryRun(CommandSender sender) {
        backfillService.dryRun().whenComplete((report, ex) -> {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                if (ex != null) {
                    sender.sendMessage(Component.text("Backfill dry-run błąd: " + ex.getMessage(), NamedTextColor.RED));
                    return;
                }
                sendReport(sender, report, true);
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int backfillExecute(CommandSender sender) {
        if (!sender.hasPermission("skyblockgameplay.admin.backfill")) {
            sender.sendMessage(Component.text("Brak uprawnień do backfill execute (skyblockgameplay.admin.backfill)", NamedTextColor.RED));
            return 0;
        }
        backfillService.backfill(false).whenComplete((report, ex) -> {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                if (ex != null) {
                    sender.sendMessage(Component.text("Backfill błąd: " + ex.getMessage(), NamedTextColor.RED));
                    return;
                }
                sendReport(sender, report, false);
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private void sendReport(CommandSender sender, BackfillReport report, boolean dryRun) {
        sender.sendMessage(mm.deserialize("<gold><bold>" + (dryRun ? "DRY-RUN" : "BACKFILL") + " report</bold></gold>"));
        sender.sendMessage(Component.text("  checksumBefore: " + report.checksumBefore(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  checksumAfter: " + report.checksumAfter() + " matches=" + report.checksumMatches(), report.checksumMatches() ? NamedTextColor.GREEN : NamedTextColor.RED));
        sender.sendMessage(Component.text("  disabledOrLocked: " + report.disabledOrLockedIslands().size(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  missingOwners: " + report.missingOwners().size(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  multipleMemberships: " + report.multipleMembershipPlayers().size(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  accountsWithoutProfile: " + report.accountsWithoutProfile().size(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  islandsBackfilled: " + report.islandsBackfilled() + " accountsMigrated: " + report.accountsMigrated() + " ambiguous: " + report.ambiguousAccounts(), NamedTextColor.GRAY));
        if (report.hasIssues()) sender.sendMessage(Component.text("  ⚠ Issues detected — ambiguous go to quarantine, not random assignment", NamedTextColor.YELLOW));
    }

    private int viewPlaytime(CommandSender sender, String playerName) {
        Player p = Bukkit.getPlayerExact(playerName);
        UUID uuid = p != null ? p.getUniqueId() : null;
        if (uuid == null) {
            try { uuid = Bukkit.getOfflinePlayer(playerName).getUniqueId(); } catch (Exception e) {
                sender.sendMessage(Component.text("Gracz nie znaleziony", NamedTextColor.RED));
                return 0;
            }
        }
        final UUID fUuid = uuid;
        profileStateService.activeMembership(fUuid).whenComplete((opt, ex) -> {
            if (opt == null || opt.isEmpty()) {
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> sender.sendMessage(Component.text("Brak aktywnego profilu", NamedTextColor.YELLOW)));
                return;
            }
            var m = opt.get();
            playtimeDao.find(m.islandId(), fUuid).whenComplete((rowOpt, ex2) -> {
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                    if (rowOpt.isEmpty()) {
                        sender.sendMessage(Component.text("Brak playtime dla profilu " + m.islandId(), NamedTextColor.GRAY));
                        return;
                    }
                    var r = rowOpt.get();
                    sender.sendMessage(mm.deserialize("<green>Playtime dla <white>" + playerName + "</white> profil <white>" + m.islandId().toString().substring(0,8) + "</white>:</green>"));
                    sender.sendMessage(Component.text("  active: " + CompactDurationFormatter.format(Duration.ofMillis(r.totalActiveMs()))
                            + " afk: " + CompactDurationFormatter.format(Duration.ofMillis(r.totalAfkMs())), NamedTextColor.GRAY));
                    sender.sendMessage(Component.text("  sessionStart: " + r.sessionStart() + " lastHeartbeat: " + r.lastHeartbeat() + " revision: " + r.revision(), NamedTextColor.GRAY));
                });
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int viewAnalytics(CommandSender sender, String playerFilter) {
        var events = analytics.recentEvents();
        if (playerFilter != null) {
            try {
                UUID filter = Bukkit.getPlayerExact(playerFilter) != null ? Bukkit.getPlayerExact(playerFilter).getUniqueId() : Bukkit.getOfflinePlayer(playerFilter).getUniqueId();
                sender.sendMessage(mm.deserialize("<green>Analytics dla <white>" + playerFilter + "</white>:</green>"));
                events.values().stream().filter(e -> e.playerUuid().equals(filter)).limit(10).forEach(e -> sender.sendMessage(Component.text("  " + e.eventType() + " op=" + e.operationId() + " corr=" + e.correlationId(), NamedTextColor.GRAY)));
                return Command.SINGLE_SUCCESS;
            } catch (Exception e) {
                sender.sendMessage(Component.text("Filtr nie znaleziony", NamedTextColor.RED));
                return 0;
            }
        }
        sender.sendMessage(mm.deserialize("<green>Analytics — ostatnie " + events.size() + " eventów (bez IP):</green>"));
        events.values().stream().limit(10).forEach(e -> sender.sendMessage(Component.text("  " + e.eventType() + " player=" + e.playerUuid().toString().substring(0,8) + " op=" + e.operationId().substring(0, Math.min(16, e.operationId().length())) + " scope=" + e.serverScope(), NamedTextColor.GRAY)));
        return Command.SINGLE_SUCCESS;
    }

    private int verifySnapshot(CommandSender sender, String snapshotId) {
        snapshotDao.verifyChecksum(snapshotId).whenComplete((ok, ex) -> {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                if (ex != null) {
                    sender.sendMessage(Component.text("Verify błąd: " + ex.getMessage(), NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text("Snapshot " + snapshotId + " checksum: " + (Boolean.TRUE.equals(ok) ? "OK" : "FAIL (corrupted)"), Boolean.TRUE.equals(ok) ? NamedTextColor.GREEN : NamedTextColor.RED));
            });
        });
        return Command.SINGLE_SUCCESS;
    }
}
