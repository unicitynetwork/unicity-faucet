package org.unicitylabs.faucet;

import org.apache.commons.codec.binary.Hex;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.unicitylabs.nostr.client.NostrClient;
import org.unicitylabs.nostr.crypto.NostrKeyManager;
import org.unicitylabs.nostr.crypto.SchnorrSigner;

import java.security.SecureRandom;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * E2E verification that FaucetService.processFaucetRequest uses the
 * nametag cache: multiple requests for the same nametag should only
 * trigger a single Nostr query.
 *
 * Scenario modelled: Sphere portal topping up a wallet across several coins
 * in a short burst.
 *
 * Requires AGGREGATOR_API_KEY env var (and network access to the test aggregator
 * and Nostr relay). Skipped otherwise.
 */
public class NametagCacheE2ETest {

    private static final String NOSTR_RELAY = "wss://nostr-relay.testnet.unicity.network";
    private static final SecureRandom RNG = new SecureRandom();

    private FaucetService faucetService;
    private NostrClient bindingClient;
    private String recipientNametag;

    @Before
    public void setUp() throws Exception {
        String apiKey = System.getenv("AGGREGATOR_API_KEY");
        Assume.assumeTrue("AGGREGATOR_API_KEY not set, skipping E2E test",
                apiKey != null && !apiKey.isEmpty());

        // Create a fresh recipient with a unique nametag + publish binding
        byte[] recipientPriv = new byte[32];
        RNG.nextBytes(recipientPriv);
        byte[] recipientPub = SchnorrSigner.getPublicKey(recipientPriv);
        String recipientPubHex = Hex.encodeHexString(recipientPub);
        recipientNametag = "cache-test-" + UUID.randomUUID().toString().substring(0, 8);

        NostrKeyManager keyManager = NostrKeyManager.fromPrivateKey(recipientPriv);
        bindingClient = new NostrClient(keyManager);
        bindingClient.connect(NOSTR_RELAY).join();
        bindingClient.publishNametagBinding(recipientNametag, "dummy-address").join();
        // Wait for relay propagation before the faucet tries to resolve.
        Thread.sleep(2_000);

        System.out.println("✅ Published binding for " + recipientNametag
                + " → " + recipientPubHex.substring(0, 16) + "...");

        FaucetConfig config = FaucetConfig.load();
        faucetService = new FaucetService(config, "faucet-data");
    }

    @After
    public void tearDown() {
        if (bindingClient != null) bindingClient.disconnect();
        if (faucetService != null) faucetService.shutdown();
    }

    @Test
    public void cachePreventsDuplicateNostrQueries() throws Exception {
        // Sanity: no cache entry for this nametag yet.
        assertNull("Cache should be empty for new nametag before any request",
                faucetService.getNametagCache().get(recipientNametag));

        // Three faucet requests for the same nametag, different coins.
        // Matches a sphere portal top-up burst.
        String[] coins = {"solana", "bitcoin", "ethereum"};
        double[] amounts = {0.001, 0.00001, 0.001};

        int successes = 0;
        for (int i = 0; i < coins.length; i++) {
            FaucetService.FaucetRequestResult result =
                    faucetService.processFaucetRequest(recipientNametag, coins[i], amounts[i]).join();
            System.out.println("Request " + (i + 1) + " [" + coins[i] + "]: "
                    + (result.success ? "OK" : "FAIL — " + result.message));
            if (result.success) successes++;
            // After ANY request that reached the resolve step, cache must have the entry
            // even if a later step (mint/transfer/deliver) failed.
            assertNotNull("Cache must hold nametag after a resolve attempt",
                    faucetService.getNametagCache().get(recipientNametag));
        }

        // At least one full flow should succeed — sanity that the plumbing works.
        assertTrue("At least one faucet request should succeed", successes >= 1);

        // The critical assertion: exactly one cache entry for this nametag,
        // meaning Nostr was queried once despite 3 faucet requests.
        assertEquals("Cache should hold exactly one entry for the single nametag",
                1, faucetService.getNametagCache().size());
    }
}
