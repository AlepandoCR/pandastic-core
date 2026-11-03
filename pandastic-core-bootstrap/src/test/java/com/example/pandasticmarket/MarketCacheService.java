package com.example.pandasticmarket;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.PandasticService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The consumer plugin's cache-holder service: a concrete class implementing {@link MarketCache}.
 * <p>
 * As a concrete non-library class it can never cross a classloader boundary itself — the bridge
 * proxies it through its interfaces — which is precisely what the trusted-package tests exercise.
 * It owns a real {@link ConcurrentHashMap} so liveness assertions compare against genuine shared
 * state, and it is its own {@code getOwnerService()} because a cache-holder fixture needs no
 * second service.
 */
public final class MarketCacheService implements MarketCache {

    /**
     * Stable service identifier, in the {@code Name_Plugin} style consumer plugins use.
     */
    public static final String SERVICE_NAME = "MarketCache_PandasticMarket";

    private final Map<String, String> marketData = new ConcurrentHashMap<>();

    public MarketCacheService() {
    }

    @Override
    public @NotNull Map<String, String> marketData() {
        return marketData;
    }

    @Override
    public void clear() {
        marketData.clear();
    }

    @Override
    public @NotNull PandasticService getOwnerService() {
        return this;
    }

    @Override
    public @NotNull String getServiceName() {
        return SERVICE_NAME;
    }

    @Override
    public @NotNull Component getName() {
        return Component.text(SERVICE_NAME);
    }

    @Override
    public @NotNull Component getDescription() {
        return Component.text("Consumer-defined market cache fixture.");
    }
}
