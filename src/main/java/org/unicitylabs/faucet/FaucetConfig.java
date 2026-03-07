package org.unicitylabs.faucet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * Configuration class for the faucet application
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FaucetConfig {

    public String registryUrl;    // URL to unicity-ids registry JSON
    public String nostrRelay;
    public String aggregatorUrl;
    public String aggregatorApiKey;
    public String faucetMnemonic; // BIP-39 mnemonic phrase
    public int defaultAmount;
    public String defaultCoin;    // Default coin name (e.g., "solana", "bitcoin")

    /**
     * Load configuration from resources, with environment variable overrides
     */
    public static FaucetConfig load() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        InputStream configStream = FaucetConfig.class
            .getResourceAsStream("/faucet-config.json");

        if (configStream == null) {
            throw new IOException("faucet-config.json not found in resources");
        }

        FaucetConfig config = mapper.readValue(configStream, FaucetConfig.class);

        // Override with environment variables if present
        String envMnemonic = System.getenv("FAUCET_MNEMONIC");
        if (envMnemonic != null && !envMnemonic.trim().isEmpty()) {
            config.faucetMnemonic = envMnemonic.trim();
            System.out.println("✅ Using FAUCET_MNEMONIC from environment variable");
        }

        String envApiKey = System.getenv("AGGREGATOR_API_KEY");
        if (envApiKey != null && !envApiKey.trim().isEmpty()) {
            config.aggregatorApiKey = envApiKey.trim();
            System.out.println("✅ Using AGGREGATOR_API_KEY from environment variable");
        }

        // Validate mnemonic is set
        if (config.faucetMnemonic == null || config.faucetMnemonic.trim().isEmpty()) {
            throw new IOException("Faucet mnemonic not configured. Set FAUCET_MNEMONIC environment variable or configure in faucet-config.json");
        }

        return config;
    }

    /**
     * Get the aggregator API key, validating it's properly configured
     */
    public String getAggregatorApiKey() {
        if (aggregatorApiKey == null || aggregatorApiKey.trim().isEmpty() || aggregatorApiKey.equals("your-api-key-here")) {
            throw new IllegalStateException(
                "AGGREGATOR_API_KEY not configured. Set AGGREGATOR_API_KEY env var or aggregatorApiKey in faucet-config.json");
        }
        return aggregatorApiKey;
    }
}
