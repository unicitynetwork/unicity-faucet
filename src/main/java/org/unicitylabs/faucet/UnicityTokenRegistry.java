package org.unicitylabs.faucet;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry for Unicity token/coin definitions
 * Caches metadata from configurable registry URL
 */
public class UnicityTokenRegistry {

    private static final Logger logger = LoggerFactory.getLogger(UnicityTokenRegistry.class);

    private static final String CACHE_FILE = System.getProperty("user.home") + "/.unicity/registry-cache.json";
    private static final long CACHE_VALIDITY_HOURS = 24;

    private static UnicityTokenRegistry instance;
    private static String registryUrl; // Configurable registry URL
    private final Map<String, CoinDefinition> coinsById;

    public static class IconEntry {
        public String url;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class CoinDefinition {
        public String network;
        public String assetKind;
        public String name;
        public String symbol;
        public Integer decimals; // Can be null if not specified
        public String description;
        public String icon; // Legacy field (deprecated)
        public List<IconEntry> icons; // New icons array
        public String id;

        /**
         * Get the best icon URL (prefer PNG over SVG)
         */
        public String getIconUrl() {
            // Try new icons array first
            if (icons != null && !icons.isEmpty()) {
                // Prefer PNG
                for (IconEntry iconEntry : icons) {
                    if (iconEntry.url.toLowerCase().contains(".png")) {
                        return iconEntry.url;
                    }
                }
                // Fall back to first icon
                return icons.get(0).url;
            }

            // Fall back to legacy icon field
            return icon;
        }

        @Override
        public String toString() {
            return String.format("%s (%s) - decimals: %d", name, symbol, decimals);
        }
    }

    private UnicityTokenRegistry(String url) throws IOException {
        this.registryUrl = url;
        ObjectMapper mapper = new ObjectMapper();
        List<CoinDefinition> definitions;

        // Try to load from cache first
        File cacheFile = new File(CACHE_FILE);
        if (cacheFile.exists() && !isCacheStale(cacheFile)) {
            logger.info("Loading registry from cache: {}", CACHE_FILE);
            definitions = mapper.readValue(
                cacheFile,
                mapper.getTypeFactory().constructCollectionType(List.class, CoinDefinition.class)
            );
        } else {
            // Fetch from configured URL
            logger.info("Fetching registry from: {}", url);
            URL registryURL = new URL(url);
            definitions = mapper.readValue(
                registryURL,
                mapper.getTypeFactory().constructCollectionType(List.class, CoinDefinition.class)
            );

            // Save to cache
            saveToCache(mapper, definitions);
        }

        // Index by ID
        coinsById = new HashMap<>();
        for (CoinDefinition def : definitions) {
            coinsById.put(def.id, def);
        }

        logger.info("Token registry loaded: {} definitions", coinsById.size());
    }

    private boolean isCacheStale(File cacheFile) {
        long ageMillis = System.currentTimeMillis() - cacheFile.lastModified();
        long ageHours = ageMillis / (1000 * 60 * 60);
        return ageHours > CACHE_VALIDITY_HOURS;
    }

    private void saveToCache(ObjectMapper mapper, List<CoinDefinition> definitions) {
        try {
            File cacheFile = new File(CACHE_FILE);
            cacheFile.getParentFile().mkdirs(); // Create ~/.unicity directory if needed
            mapper.writeValue(cacheFile, definitions);
            logger.debug("Registry cached to: {}", CACHE_FILE);
        } catch (IOException e) {
            logger.warn("Failed to cache registry", e);
        }
    }

    /**
     * Clear the registry cache file
     */
    public static synchronized void clearCache() {
        File cacheFile = new File(CACHE_FILE);
        if (cacheFile.exists()) {
            boolean deleted = cacheFile.delete();
            if (deleted) {
                logger.info("Registry cache cleared: {}", CACHE_FILE);
            } else {
                logger.warn("Failed to delete cache file: {}", CACHE_FILE);
            }
        } else {
            logger.debug("No cache file to clear");
        }
    }

    /**
     * Refresh registry data from remote URL
     * Clears cache and reloads into existing instance
     */
    public synchronized void refresh() throws IOException {
        clearCache();

        ObjectMapper mapper = new ObjectMapper();
        logger.info("Refreshing registry from: {}", registryUrl);
        URL url = new URL(registryUrl);
        List<CoinDefinition> definitions = mapper.readValue(
            url,
            mapper.getTypeFactory().constructCollectionType(List.class, CoinDefinition.class)
        );

        // Save to cache
        saveToCache(mapper, definitions);

        // Re-index
        coinsById.clear();
        for (CoinDefinition def : definitions) {
            coinsById.put(def.id, def);
        }

        logger.info("Registry refreshed: {} definitions", coinsById.size());
    }

    /**
     * Initialize and get registry instance with config URL
     */
    public static synchronized UnicityTokenRegistry getInstance(String url) throws IOException {
        if (instance == null) {
            instance = new UnicityTokenRegistry(url);
        }
        return instance;
    }

    /**
     * Get existing instance (must call getInstance(url) first)
     */
    public static synchronized UnicityTokenRegistry getInstance() throws IOException {
        if (instance == null) {
            throw new IllegalStateException("Registry not initialized. Call getInstance(url) first.");
        }
        return instance;
    }

    /**
     * Get coin definition by coin ID hex
     */
    public CoinDefinition getCoinDefinition(String coinIdHex) {
        return coinsById.get(coinIdHex);
    }

    /**
     * Get coin definition by name (e.g., "solana", "bitcoin")
     * Only searches FUNGIBLE assets (excludes non-fungible token types)
     * If not found in cache, refreshes from online registry
     *
     * Thread-safe: Uses synchronized refresh to prevent concurrent registry replacement
     */
    public synchronized CoinDefinition getCoinByName(String name) throws IOException {
        // First check cache - ONLY fungible assets
        for (CoinDefinition coin : coinsById.values()) {
            if ("fungible".equals(coin.assetKind) &&
                coin.name != null &&
                coin.name.equalsIgnoreCase(name)) {
                return coin;
            }
        }

        // Not found in cache - refresh this instance (don't replace singleton)
        logger.info("Fungible coin '{}' not found in cache, refreshing from online registry", name);
        refresh();

        // Search again after refresh - ONLY fungible assets
        for (CoinDefinition coin : coinsById.values()) {
            if ("fungible".equals(coin.assetKind) &&
                coin.name != null &&
                coin.name.equalsIgnoreCase(name)) {
                logger.info("Found fungible coin '{}' after refresh", name);
                return coin;
            }
        }

        return null;
    }

    /**
     * Get all fungible coins
     */
    public List<CoinDefinition> getFungibleCoins() {
        List<CoinDefinition> fungible = new java.util.ArrayList<>();
        for (CoinDefinition coin : coinsById.values()) {
            if ("fungible".equals(coin.assetKind)) {
                fungible.add(coin);
            }
        }
        return fungible;
    }

    /**
     * Get the Unicity token type (non-fungible "unicity" asset)
     */
    public String getUnicityTokenType() {
        for (CoinDefinition def : coinsById.values()) {
            if ("non-fungible".equals(def.assetKind) && "unicity".equalsIgnoreCase(def.name)) {
                return def.id;
            }
        }
        return null;
    }

    /**
     * Get decimals for a coin, returns 8 as default if not found
     */
    public int getDecimals(String coinIdHex) {
        CoinDefinition coin = getCoinDefinition(coinIdHex);
        if (coin != null && coin.decimals != null) {
            return coin.decimals;
        }
        return 8; // Default to 8 decimals
    }
}
