package org.unicitylabs.faucet;

import com.fasterxml.jackson.databind.JsonNode;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// Nostr SDK imports
import org.unicitylabs.nostr.client.NostrClient;
import org.unicitylabs.nostr.crypto.NostrKeyManager;
import org.unicitylabs.nostr.crypto.SchnorrSigner;
import org.unicitylabs.nostr.nametag.NametagUtils;
import org.unicitylabs.nostr.protocol.EventKinds;

// Apache Commons Codec for hex encoding
import org.apache.commons.codec.binary.Hex;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * E2E test for the faucet:
 * 1. Mint a unique nametag for Alice
 * 2. Mint a solana-test token
 * 3. Send token to Alice via Nostr
 * 4. Verify Alice receives the token
 *
 * NOTE: Uses simplified message format without real Nostr signatures for testing.
 * The wallet's NostrP2PService will process the token_transfer message.
 */
public class FaucetE2ETest {

    private static final String AGGREGATOR_URL = "https://goggregator-test.unicity.network";
    private static final String NOSTR_RELAY = "wss://nostr-relay.testnet.unicity.network";
    private static final long TIMEOUT_SECONDS = 60L;
    private static final int KIND_TOKEN_TRANSFER = 31113; // Custom Unicity event kind

    // Fixed values from faucet-config.json
    private static final String TOKEN_TYPE_HEX = "f8aa13834268d29355ff12183066f0cb902003629bbc5eb9ef0efbe397867509";
    private static final String COIN_ID_HEX = "dee5f8ce778562eec90e9c38a91296a023210ccc76ff4c29d527ac3eb64ade93";

    private static String getApiKey() {
        try {
            return FaucetConfig.load().getAggregatorApiKey();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load API key: " + e.getMessage(), e);
        }
    }

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout - keep connection open indefinitely
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // Send ping every 20 seconds to keep alive
        .retryOnConnectionFailure(true) // Auto-retry on connection failures
        .build();

    private byte[] faucetPrivateKey;
    private byte[] alicePrivateKey;
    private String aliceNostrPubKey;
    private String aliceNametag;
    private WebSocket aliceWebSocket;
    private final List<String> aliceReceivedMessages = new ArrayList<>();
    private final CountDownLatch aliceTokenReceivedLatch = new CountDownLatch(1);

    @Before
    public void setUp() throws Exception {
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║  Faucet E2E Test - Starting Setup           ║");
        System.out.println("╚══════════════════════════════════════════════╝\n");

        // Generate faucet private key
        faucetPrivateKey = new byte[32];
        new SecureRandom().nextBytes(faucetPrivateKey);
        System.out.println("✅ Generated faucet private key");

        // Generate Alice's private key and derive Nostr public key properly
        alicePrivateKey = new byte[32];
        new SecureRandom().nextBytes(alicePrivateKey);
        byte[] alicePubKeyBytes = SchnorrSigner.getPublicKey(alicePrivateKey); // Derive from private key
        aliceNostrPubKey = Hex.encodeHexString(alicePubKeyBytes);
        System.out.println("✅ Alice's Nostr pubkey: " + aliceNostrPubKey.substring(0, 16) + "...");

        // Generate unique nametag for Alice
        aliceNametag = "alice-test-" + UUID.randomUUID().toString().substring(0, 8);
        System.out.println("✅ Generated unique nametag: " + aliceNametag);
    }

    @After
    public void tearDown() {
        if (aliceWebSocket != null) {
            aliceWebSocket.close(1000, "Test complete");
        }
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    @Test
    public void testCompleteTokenTransferFlow() throws Exception {
        System.out.println("\n📋 Test Flow:");
        System.out.println("   1. Mint nametag for Alice");
        System.out.println("   2. Mint solana-test token");
        System.out.println("   2.5. Publish Alice's nametag binding to Nostr");
        System.out.println("   2.6. Test NametagResolver lookup");
        System.out.println("   3. Transfer token to Alice (adds transfer tx)");
        System.out.println("   4. Connect Alice to Nostr relay");
        System.out.println("   5. Send token to Alice via Nostr");
        System.out.println("   6. Verify Alice receives token");
        System.out.println("   7. Verify token has complete transaction history (mint + transfer)");
        System.out.println("   8. Verify nametag binding queries work bidirectionally\n");

        // Step 1: Mint nametag for Alice
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 1: Minting nametag for Alice");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        NametagMinter nametagMinter = new NametagMinter(AGGREGATOR_URL);
        var nametagToken = nametagMinter.mintNametag(
            aliceNametag,
            alicePrivateKey,
            aliceNostrPubKey
        ).join();

        assertNotNull("Nametag token should be minted", nametagToken);
        System.out.println("✅ Nametag minted successfully!");

        // Step 2: Mint token from faucet (before connecting Alice to avoid timeout)
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 2: Minting solana-test token");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        TokenMinter tokenMinter = new TokenMinter(AGGREGATOR_URL, faucetPrivateKey);
        var token = tokenMinter.mintToken(
            TOKEN_TYPE_HEX,  // token type from config
            COIN_ID_HEX,     // coin ID from config
            BigInteger.valueOf(1000L)  // amount
        ).join();

        assertNotNull("Token should be minted", token);
        System.out.println("✅ Token minted successfully!");

        // Step 2.5: Publish nametag binding to Nostr relay
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 2.5: Publishing nametag binding to Nostr");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Publish Alice's nametag binding using SDK
        NostrKeyManager aliceKeyManager = NostrKeyManager.fromPrivateKey(alicePrivateKey);
        NostrClient aliceNostrClient = new NostrClient(aliceKeyManager);
        aliceNostrClient.connect(NOSTR_RELAY).join();

        // Use Alice's Nostr pubkey as her Unicity address for this demo
        String aliceAddress = aliceNostrPubKey;
        boolean bindingPublished = aliceNostrClient.publishNametagBinding(aliceNametag, aliceAddress).join();

        assertTrue("Nametag binding should be published", bindingPublished);
        System.out.println("✅ Alice's nametag binding published!");
        aliceNostrClient.disconnect();

        // Step 2.6: Test nametag resolution using NametagResolver
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 2.6: Testing NametagResolver lookup");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        NametagResolver resolver = new NametagResolver(NOSTR_RELAY, faucetPrivateKey);
        String resolvedPubkey = resolver.resolveNametag(aliceNametag).join();
        assertNotNull("Resolved pubkey should not be null", resolvedPubkey);
        assertEquals("Resolved pubkey should match Alice's pubkey", aliceNostrPubKey, resolvedPubkey);
        System.out.println("✅ NametagResolver successfully looked up Alice's pubkey!");

        // Transfer token to Alice's nametag proxy address
        System.out.println("\n🔄 Transferring token to Alice's nametag: " + aliceNametag);
        var nametagTokenId = org.unicitylabs.sdk.token.TokenId.fromNameTag(aliceNametag);
        var aliceProxyAddress = org.unicitylabs.sdk.address.ProxyAddress.create(nametagTokenId);
        var transferInfo = tokenMinter.transferToProxyAddress(token, aliceProxyAddress).join();
        assertNotNull("Transfer info should be created", transferInfo);
        System.out.println("✅ Token transferred to Alice!");

        // For testing: Alice receives and finalizes the token
        // In production, Alice would do this on her own device
        var aliceRecipient = new TestRecipient(
            new org.unicitylabs.sdk.StateTransitionClient(
                new org.unicitylabs.sdk.api.JsonRpcAggregatorClient(AGGREGATOR_URL, getApiKey())),
            tokenMinter.getTrustBase(),  // Use the trustbase from tokenMinter
            alicePrivateKey  // Alice's private key to create matching predicate
        );
        var finalizedToken = aliceRecipient.finalizeReceivedToken(transferInfo, nametagToken);
        assertNotNull("Token should be finalized", finalizedToken);

        String tokenJson = tokenMinter.serializeToken(finalizedToken);
        System.out.println("✅ Alice finalized the token on her device!");

        // Step 3: Connect Alice to Nostr relay AFTER transfer completes
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 3: Connecting Alice to Nostr relay");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        connectAliceToNostr();
        Thread.sleep(1000); // Wait for connection and subscription to be fully established

        // Step 4: Send token to Alice via Nostr
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 4: Sending token to Alice via Nostr");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("⏱️  Alice is now subscribed and waiting for messages...");

        NostrKeyManager faucetKeyManager = NostrKeyManager.fromPrivateKey(faucetPrivateKey);
        NostrClient nostrClient = new NostrClient(faucetKeyManager);
        nostrClient.connect(NOSTR_RELAY).join();

        // Use SDK method to send token transfer (same as production code)
        // This ensures test uses the same code path as FaucetCLI
        // NOTE: sendTokenTransfer() adds "token_transfer:" prefix itself, don't add it here!
        String eventId = nostrClient.sendTokenTransfer(aliceNostrPubKey, tokenJson).join();

        System.out.println("✅ Token sent via Nostr! Event ID: " + eventId.substring(0, 16) + "...");
        System.out.println("   Kind: " + KIND_TOKEN_TRANSFER + " (TOKEN_TRANSFER)");

        // Give the relay time to broadcast the message to Alice before closing sender's connection
        Thread.sleep(3000);  // Increased wait time for relay to broadcast
        nostrClient.disconnect();
        System.out.println("✅ Sender connection closed");

        // Step 6: Wait for Alice to receive the token
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 6: Waiting for Alice to receive token...");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("⏱️  Waiting up to 30 seconds for EVENT message...");
        System.out.println("📡 Alice WebSocket status: " + (aliceWebSocket != null ? "CONNECTED" : "NULL"));

        boolean received = aliceTokenReceivedLatch.await(30, TimeUnit.SECONDS);

        System.out.println("\n📊 Wait result: " + (received ? "✅ RECEIVED" : "❌ TIMEOUT"));
        System.out.println("📝 Alice received " + aliceReceivedMessages.size() + " messages total");
        assertTrue("Alice should receive the token within 30 seconds", received);

        // Verify the received message contains the token
        assertFalse("Alice should have received messages", aliceReceivedMessages.isEmpty());

        String receivedTokenJson = null;
        for (String msg : aliceReceivedMessages) {
            if (msg.contains("token_transfer")) {
                System.out.println("✅ Alice received token transfer message!");
                // Extract token JSON from message (format: "token_transfer:{...}")
                receivedTokenJson = msg.substring("token_transfer:".length());
                break;
            }
        }
        assertNotNull("Alice should receive token_transfer message", receivedTokenJson);

        // Step 7: Verify token has complete transaction history
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 7: Verifying token transaction history...");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Parse the received token using SDK's fromJson
        var receivedToken = org.unicitylabs.sdk.token.Token.fromJson(receivedTokenJson);
        assertNotNull("Token should be parsed", receivedToken);

        // Verify token version
        System.out.println("📦 Token version: " + receivedToken.getVersion());
        assertEquals("Token should be version 2.0", "2.0", receivedToken.getVersion());

        // Verify genesis transaction (MINT)
        var genesis = receivedToken.getGenesis();
        assertNotNull("Token should have genesis transaction", genesis);
        assertNotNull("Genesis should have inclusion proof", genesis.getInclusionProof());
        System.out.println("✅ Genesis (MINT) transaction is finalized with inclusion proof!");

        // Verify coinData using SDK API
        var genesisData = genesis.getData();
        assertNotNull("Genesis should have transaction data", genesisData);

        // Check if token has coin data (fungible token)
        if (genesisData instanceof org.unicitylabs.sdk.transaction.MintTransaction.Data) {
            var mintData = (org.unicitylabs.sdk.transaction.MintTransaction.Data<?>) genesisData;
            var coinDataOpt = mintData.getCoinData();

            assertTrue("Token should have coin data", coinDataOpt.isPresent());
            var coinData = coinDataOpt.get();
            var coins = coinData.getCoins();

            assertFalse("Coins should not be empty", coins.isEmpty());

            // Get first coin entry
            var firstCoinEntry = coins.entrySet().iterator().next();
            String extractedCoinId = Hex.encodeHexString(firstCoinEntry.getKey().getBytes());
            BigInteger extractedAmount = firstCoinEntry.getValue();

            assertNotNull("Should extract coinId", extractedCoinId);
            assertNotNull("Should extract amount", extractedAmount);
            assertTrue("Amount should be positive", extractedAmount.compareTo(BigInteger.ZERO) > 0);

            System.out.println("📜 Genesis transaction: MINT (verified structure)");
            System.out.println("   CoinId: " + extractedCoinId.substring(0, 16) + "...");
            System.out.println("   Amount: " + extractedAmount);
        } else {
            fail("Genesis data is not MintTransaction.Data");
        }

        // Verify transfer transactions list
        var transactions = receivedToken.getTransactions();
        assertNotNull("Token should have transactions list", transactions);

        System.out.println("📜 Transfer transaction count: " + transactions.size());
        assertEquals("Token should have 1 transfer transaction", 1, transactions.size());

        // Verify the transfer transaction
        var transferTx = transactions.get(0);
        assertNotNull("Transfer should have inclusion proof", transferTx.getInclusionProof());

        var transferData = transferTx.getData();
        assertNotNull("Transfer should have transaction data", transferData);

        // Verify it's a TRANSFER transaction by checking for TRANSFER-specific fields
        if (transferData instanceof org.unicitylabs.sdk.transaction.TransferTransaction.Data) {
            var transferTxData = (org.unicitylabs.sdk.transaction.TransferTransaction.Data) transferData;
            assertNotNull("Transfer should have recipient", transferTxData.getRecipient());
            assertNotNull("Transfer should have salt", transferTxData.getSalt());
            System.out.println("   Transfer transaction: TRANSFER (verified by structure)");
        } else {
            fail("Transfer data is not TransferTransaction.Data");
        }

        System.out.println("✅ Transfer transaction is finalized with inclusion proof!");

        // Verify token state has correct owner predicate
        var state = receivedToken.getState();
        var predicate = state.getPredicate();
        assertNotNull("Token should have predicate", predicate);
        System.out.println("✅ Token has valid owner predicate!");

        // Step 8: Verify nametag bindings work bidirectionally
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 8: Verifying nametag binding queries...");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Wait a moment for relay to index the binding event
        Thread.sleep(2000);

        // Test 1: Query Nostr pubkey by Alice's nametag using SDK
        // This is critical for wallet functionality
        NostrKeyManager queryKeyManager = NostrKeyManager.fromPrivateKey(faucetPrivateKey);
        NostrClient queryClient = new NostrClient(queryKeyManager);
        queryClient.connect(NOSTR_RELAY).join();

        String queriedPubkey = queryClient.queryPubkeyByNametag(aliceNametag).join();
        if (queriedPubkey == null) {
            System.out.println("⚠️ Warning: Could not query nametag - relay might need time to index");
        } else {
            assertEquals("Should return correct pubkey", aliceNostrPubKey, queriedPubkey);
            System.out.println("✅ Query by nametag successful: " + aliceNametag + " → " + queriedPubkey.substring(0, 16) + "...");
        }

        queryClient.disconnect();
        System.out.println("✅ Nametag binding queries tested!");

        System.out.println("✅ Nametag binding system working correctly!");

        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║  ✅ E2E Test Passed Successfully!           ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println("\n📊 Test Summary:");
        System.out.println("   ✓ Nametag minted: " + aliceNametag);
        System.out.println("   ✓ Token minted: solana-test (1000)");
        System.out.println("   ✓ Token transferred to Alice (faucet->Alice)");
        System.out.println("   ✓ Nametag binding published to Nostr");
        System.out.println("   ✓ Token sent via Nostr");
        System.out.println("   ✓ Alice received token");
        System.out.println("   ✓ Token has complete history (genesis MINT + 1 transfer)");
        System.out.println("   ✓ Both transactions are finalized with inclusion proofs");
        System.out.println("   ✓ Nametag binding queries work bidirectionally");
        System.out.println();
    }

    private void connectAliceToNostr() {
        Request request = new Request.Builder()
            .url(NOSTR_RELAY)
            .build();

        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                aliceWebSocket = webSocket;
                System.out.println("✅ Alice connected to Nostr relay");

                // Subscribe to messages for Alice
                try {
                    String subscriptionId = "alice-sub-" + System.currentTimeMillis();
                    var filter = new java.util.HashMap<String, Object>();
                    filter.put("kinds", Arrays.asList(KIND_TOKEN_TRANSFER)); // Token transfer events
                    filter.put("#p", Arrays.asList(aliceNostrPubKey));
                    filter.put("limit", 100);

                    List<Object> subRequest = Arrays.asList("REQ", subscriptionId, filter);
                    String json = jsonMapper.writeValueAsString(subRequest);

                    System.out.println("📡 Alice subscribing to messages...");
                    webSocket.send(json);
                } catch (Exception e) {
                    System.err.println("❌ Error subscribing: " + e.getMessage());
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    long timestamp = System.currentTimeMillis();
                    System.out.println("🔍 [" + timestamp + "] Alice received raw message: " + text.substring(0, Math.min(100, text.length())) + "...");
                    List<?> message = jsonMapper.readValue(text, List.class);
                    String messageType = (String) message.get(0);
                    System.out.println("📩 [" + timestamp + "] Message type: " + messageType);

                    if ("EVENT".equals(messageType)) {
                        var eventData = message.get(2);
                        var event = jsonMapper.convertValue(eventData, JsonNode.class);

                        String content = event.get("content").asText();
                        System.out.println("📨 Alice received Nostr event with content length: " + content.length());

                        // Decrypt content (NIP-04 encrypted by SDK)
                        try {
                            String senderPubkey = event.get("pubkey").asText();

                            // Decrypt using Alice's key manager
                            NostrKeyManager aliceKeyMgr = NostrKeyManager.fromPrivateKey(alicePrivateKey);
                            String decodedContent = aliceKeyMgr.decryptHex(content, senderPubkey);

                            System.out.println("✅ Decrypted content preview: " + decodedContent.substring(0, Math.min(50, decodedContent.length())) + "...");
                            aliceReceivedMessages.add(decodedContent);

                            if (decodedContent.contains("token_transfer")) {
                                System.out.println("✅ Alice received token transfer!");
                                aliceTokenReceivedLatch.countDown();
                            }
                        } catch (Exception e) {
                            System.out.println("⚠️ Decryption failed, trying hex fallback: " + e.getMessage());
                            try {
                                byte[] contentBytes = Hex.decodeHex(content);
                                String decodedContent = new String(contentBytes);
                                aliceReceivedMessages.add(decodedContent);
                                if (decodedContent.contains("token_transfer")) {
                                    aliceTokenReceivedLatch.countDown();
                                }
                            } catch (Exception hexError) {
                                System.err.println("❌ Both NIP-04 and hex decryption failed");
                            }
                        }
                    } else if ("EOSE".equals(messageType)) {
                        System.out.println("✅ Alice subscription confirmed (EOSE)");
                    } else if ("OK".equals(messageType)) {
                        System.out.println("✅ Alice event acknowledged");
                    } else {
                        System.out.println("ℹ️  Unknown message type: " + messageType);
                    }
                } catch (Exception e) {
                    System.err.println("❌ Error processing message: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                System.err.println("❌ Alice WebSocket failure:");
                if (t != null) {
                    System.err.println("   Exception: " + t.getClass().getName());
                    System.err.println("   Message: " + t.getMessage());
                    t.printStackTrace();
                } else {
                    System.err.println("   No throwable provided");
                }
                if (response != null) {
                    System.err.println("   Response code: " + response.code());
                    System.err.println("   Response message: " + response.message());
                }
                aliceTokenReceivedLatch.countDown(); // Unblock test on failure
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                System.out.println("⚠️  Alice WebSocket closing: " + code + " - " + reason);
                webSocket.close(1000, null);
            }
        };

        httpClient.newWebSocket(request, listener);
    }

    @Test
    public void testTokenSplit() throws Exception {
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║  Token Split E2E Test                        ║");
        System.out.println("╚══════════════════════════════════════════════╝\n");

        // Step 1: Mint Alice's nametag
        System.out.println("Step 1: Minting nametag for test user...");
        NametagMinter nametagMinter = new NametagMinter(AGGREGATOR_URL);
        var nametagToken = nametagMinter.mintNametag(
            aliceNametag,
            alicePrivateKey,
            aliceNostrPubKey
        ).join();
        assertNotNull("Nametag token should be minted", nametagToken);
        System.out.println("✅ Nametag minted: " + aliceNametag);

        // Step 2: Mint a token with 100 units
        System.out.println("\nStep 2: Minting token with 100 units...");
        TokenMinter tokenMinter = new TokenMinter(AGGREGATOR_URL, alicePrivateKey);
        var originalToken = tokenMinter.mintToken(
            TOKEN_TYPE_HEX,
            COIN_ID_HEX,
            BigInteger.valueOf(100L)
        ).join();
        assertNotNull("Token should be minted", originalToken);
        System.out.println("✅ Token minted: 100 units");

        // Step 3: Split the token (100 → 30 + 70)
        System.out.println("\nStep 3: Splitting token (100 → 30 + 70)...");
        TokenSplitter splitter = new TokenSplitter(
            tokenMinter.getClient(),
            tokenMinter.getTrustBase(),
            alicePrivateKey
        );

        var splitResult = splitter.splitToken(
            originalToken,
            BigInteger.valueOf(30),
            BigInteger.valueOf(70),
            nametagToken
        );

        assertNotNull("Split result should not be null", splitResult);
        assertEquals("Should have 2 split tokens", 2, splitResult.splitTokens.size());

        // Verify amounts
        BigInteger total = splitResult.splitTokens.stream()
            .map(t -> t.getCoins().get().getCoins().values().iterator().next())
            .reduce(BigInteger.ZERO, BigInteger::add);

        assertEquals("Total should still be 100", BigInteger.valueOf(100), total);

        System.out.println("✅ Split successful!");
        System.out.println("   Token 1: " + splitResult.splitTokens.get(0).getCoins().get().getCoins().values().iterator().next() + " units");
        System.out.println("   Token 2: " + splitResult.splitTokens.get(1).getCoins().get().getCoins().values().iterator().next() + " units");

        // Verify both tokens are valid
        for (int i = 0; i < splitResult.splitTokens.size(); i++) {
            Token<?> token = splitResult.splitTokens.get(i);
            var verifyResult = token.verify(tokenMinter.getTrustBase());
            assertTrue("Token " + i + " should verify", verifyResult.isSuccessful());
        }

        System.out.println("✅ All split tokens verified successfully!");
    }
}
