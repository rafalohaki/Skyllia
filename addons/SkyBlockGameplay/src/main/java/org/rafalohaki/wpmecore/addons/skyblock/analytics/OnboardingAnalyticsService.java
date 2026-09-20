package org.rafalohaki.wpmecore.addons.skyblock.analytics;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * M1-D: eventy/analityka onboardingu bez IP.
 * <p>
 * Onboarding events are emitted for stage transitions (create island, first shop trade, etc.)
 * without storing IP, device identifier, or secrets. Each event is correlated by
 * operationId/correlationId and contains only: playerUuid, profileId, islandMode,
 * stage, timestamp, serverScope. The service is intentionally minimal: it logs
 * structured events (JSON) to the plugin logger and keeps an in-memory ring for
 * admin inspection. No PII, no IP, no webhook payload with secrets.
 */
public final class OnboardingAnalyticsService {

    private static final Logger LOG = Logger.getLogger(OnboardingAnalyticsService.class.getName());

    private final String serverScope;
    private final Map<String, AnalyticsEvent> recentEvents = new ConcurrentHashMap<>();
    private static final int MAX_RECENT = 200;

    public OnboardingAnalyticsService(@NotNull String serverScope) {
        this.serverScope = serverScope;
    }

    public record AnalyticsEvent(
            @NotNull String eventType,
            @NotNull UUID playerUuid,
            @Nullable UUID profileId,
            @Nullable String islandMode,
            @NotNull String correlationId,
            @NotNull String operationId,
            long timestamp,
            @NotNull String serverScope,
            @NotNull Map<String, String> attributes) { }

    /**
     * Emit an onboarding event WITHOUT IP. Attributes are bounded and never contain
     * IP, secrets, or chat content.
     */
    public void emit(
            @NotNull String eventType,
            @NotNull UUID playerUuid,
            @Nullable UUID profileId,
            @Nullable String islandMode,
            @NotNull String correlationId,
            @NotNull String operationId,
            @NotNull Map<String, String> attributes) {
        // Defensive: strip any forbidden keys if caller mistakenly passed them
        if (attributes.containsKey("ip") || attributes.containsKey("ip_address") || attributes.containsKey("secret") || attributes.containsKey("token")) {
            LOG.warning("Analytics event rejected: attempted to include forbidden PII keys for " + eventType);
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, String> safe = Map.copyOf(attributes);
        AnalyticsEvent evt = new AnalyticsEvent(eventType, playerUuid, profileId, islandMode, correlationId, operationId, now, serverScope, safe);
        // Bounded recent store
        if (recentEvents.size() >= MAX_RECENT) {
            // evict oldest (approx): remove first key
            String first = recentEvents.keySet().iterator().next();
            recentEvents.remove(first);
        }
        recentEvents.put(operationId, evt);
        // Structured log without IP
        LOG.info("[analytics] " + eventType + " player=" + playerUuid + " profile=" + profileId + " mode=" + islandMode + " op=" + operationId + " corr=" + correlationId + " attrs=" + safe);
    }

    public void onboardingStarted(@NotNull UUID playerUuid, @NotNull String operationId, @NotNull String correlationId) {
        emit("onboarding_started", playerUuid, null, null, correlationId, operationId, Map.of("stage", "hub_arrival"));
    }

    public void islandCreated(@NotNull UUID playerUuid, @NotNull UUID profileId, @NotNull String mode, @NotNull String operationId, @NotNull String correlationId) {
        emit("island_created", playerUuid, profileId, mode, correlationId, operationId, Map.of("stage", "island_create", "mode", mode));
    }

    public void stageAdvanced(@NotNull UUID playerUuid, @Nullable UUID profileId, int stage, @NotNull String stageName, @NotNull String operationId, @NotNull String correlationId) {
        emit("stage_advanced", playerUuid, profileId, null, correlationId, operationId, Map.of("stage", String.valueOf(stage), "stageName", stageName));
    }

    public @NotNull Map<String, AnalyticsEvent> recentEvents() {
        return Map.copyOf(recentEvents);
    }

    public @NotNull String serverScope() {
        return serverScope;
    }
}
