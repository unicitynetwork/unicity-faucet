package org.unicitylabs.faucet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.nostr.client.NostrClient;
import org.unicitylabs.nostr.crypto.NostrKeyManager;

import java.util.concurrent.CompletableFuture;

/**
 * Resolves nametag to Nostr public key by querying the Nostr relay
 * Uses the nametag binding system we implemented
 */
public class NametagResolver {

    private static final Logger logger = LoggerFactory.getLogger(NametagResolver.class);

    private final String relayUrl;
    private final byte[] faucetPrivateKey;

    public NametagResolver(String relayUrl, byte[] faucetPrivateKey) {
        this.relayUrl = relayUrl;
        this.faucetPrivateKey = faucetPrivateKey;
    }

    /**
     * Resolve a nametag to a Nostr public key by querying the Nostr relay
     *
     * @param nametag The nametag to resolve (e.g., "alice-test-abc123")
     * @return CompletableFuture with the Nostr public key (hex)
     */
    public CompletableFuture<String> resolveNametag(String nametag) {
        logger.trace("Resolving nametag via Nostr relay: {}", nametag);

        try {
            // Create Nostr client with SDK
            NostrKeyManager keyManager = NostrKeyManager.fromPrivateKey(faucetPrivateKey);
            NostrClient nostrClient = new NostrClient(keyManager);

            // Connect to relay and query nametag
            return nostrClient.connect(relayUrl)
                .thenCompose(v -> nostrClient.queryPubkeyByNametag(nametag))
                .thenApply(pubkey -> {
                    if (pubkey == null) {
                        throw new RuntimeException("Nametag not found: " + nametag);
                    }
                    logger.trace("Resolved nametag '{}' → {}", nametag, pubkey.substring(0, 16));
                    nostrClient.disconnect();
                    return pubkey;
                })
                .exceptionally(e -> {
                    nostrClient.disconnect();
                    throw new RuntimeException(e);
                });
        } catch (Exception e) {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }
}
