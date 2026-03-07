package org.unicitylabs.faucet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.nostr.client.NostrClient;
import org.unicitylabs.nostr.crypto.NostrKeyManager;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/**
 * Test token transfer sizes with NIP-04 encryption + GZIP compression.
 */
public class NostrTransferSizeTest {
    private static final Logger log = LoggerFactory.getLogger(NostrTransferSizeTest.class);

    private static final String RELAY_URL = "wss://nostr-relay.testnet.unicity.network";
    private static final int KIND_TOKEN_TRANSFER = 31113;

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build();

    @Test
    public void testSend500KBTokenTransfer() throws Exception {
        log.info("=== Testing 500KB token transfer via Nostr ===");

        // Generate test keys
        byte[] senderPrivateKey = new byte[32];
        new SecureRandom().nextBytes(senderPrivateKey);
        NostrKeyManager senderKeyManager = NostrKeyManager.fromPrivateKey(senderPrivateKey);

        byte[] recipientPrivateKey = new byte[32];
        new SecureRandom().nextBytes(recipientPrivateKey);
        NostrKeyManager recipientKeyManager = NostrKeyManager.fromPrivateKey(recipientPrivateKey);

        // Test 500KB transfer
        boolean success = testTransfer(senderKeyManager, recipientKeyManager, 500);

        assertTrue("Should successfully send and receive 500KB", success);

        log.info("✅ 500KB token transfer test passed!");
    }

    @Test
    @Ignore("Manual test - run explicitly with: ./gradlew test --tests NostrTransferSizeTest.findMaximumTransferSize")
    public void findMaximumTransferSize() throws Exception {
        log.info("=== Finding Maximum Transferable File Size ===");
        log.info("Relay: {}", RELAY_URL);
        log.info("Encryption: NIP-04 (AES-256-CBC)");
        log.info("Compression: GZIP (auto for >1KB)");
        log.info("Relay limit: 1MB");
        log.info("");

        // Start at 500KB and increment by 350KB until relay rejects
        int sizeKB = 500;
        int increment = 350;
        int maxSuccessful = 0;

        while (true) {
            // Generate FRESH keys for each test to avoid cross-contamination
            byte[] senderPrivateKey = new byte[32];
            new SecureRandom().nextBytes(senderPrivateKey);
            NostrKeyManager senderKeyManager = NostrKeyManager.fromPrivateKey(senderPrivateKey);

            byte[] recipientPrivateKey = new byte[32];
            new SecureRandom().nextBytes(recipientPrivateKey);
            NostrKeyManager recipientKeyManager = NostrKeyManager.fromPrivateKey(recipientPrivateKey);

            boolean success = testTransfer(senderKeyManager, recipientKeyManager, sizeKB);

            if (success) {
                maxSuccessful = sizeKB;
                log.info("✅ {} KB - SUCCESS (verified end-to-end)", sizeKB);
                sizeKB += increment;
            } else {
                log.info("❌ {} KB - FAILED", sizeKB);
                break; // Stop at first failure
            }

            // Safety limit to prevent infinite loop
            if (sizeKB > 10000) {
                log.warn("Reached safety limit of 10MB, stopping test");
                break;
            }

            // Wait between tests to let WebSockets fully close
            Thread.sleep(500);
        }

        log.info("");
        log.info("╔══════════════════════════════════════════════╗");
        log.info("║  Maximum Transfer Size Found                 ║");
        log.info("╚══════════════════════════════════════════════╝");
        log.info("");
        log.info("📊 Results:");
        log.info("   Maximum successful: {} KB ({} MB)", maxSuccessful, String.format("%.2f", maxSuccessful / 1024.0));
        log.info("   Compression: GZIP (auto for >1KB)");
        log.info("   Encryption: NIP-04");
        log.info("   Relay limit: 1MB");
        log.info("");
        log.info("💡 Token JSON files are typically 10-20KB");
        log.info("   With 70% compression: 3-6KB encrypted");
        log.info("   Maximum tokens per transfer: ~{}", maxSuccessful / 20);
        log.info("");

        assertTrue("Should successfully send at least 500KB", maxSuccessful >= 500);
    }

    private boolean testTransfer(NostrKeyManager senderKeyManager, NostrKeyManager recipientKeyManager, int sizeKB) {
        NostrClient senderClient = null;
        WebSocket receiverWS = null;

        try {
            // Generate random JSON-like content (compresses better than random bytes)
            String originalContent = generateJsonLikeContent(sizeKB * 1024);

            log.info("Testing {} KB transfer...", sizeKB);
            log.info("  Original size: {} bytes", originalContent.length());

            // Setup receiver (Alice) to actually listen for the message
            CountDownLatch receivedLatch = new CountDownLatch(1);
            String[] receivedContent = {null};

            receiverWS = connectReceiver(recipientKeyManager, receivedLatch, receivedContent);
            Thread.sleep(1000); // Wait for receiver to subscribe

            // Send the message
            senderClient = new NostrClient(senderKeyManager);
            senderClient.connect(RELAY_URL).get(10, java.util.concurrent.TimeUnit.SECONDS);

            // Use TOKEN_TRANSFER protocol (kind 31113), same as production
            String eventId = senderClient.sendTokenTransfer(recipientKeyManager.getPublicKeyHex(), originalContent)
                .get(15, java.util.concurrent.TimeUnit.SECONDS);

            log.info("  Event ID: {}", eventId.substring(0, 16) + "...");

            // Wait for receiver to get the message (with timeout)
            boolean received = receivedLatch.await(10, TimeUnit.SECONDS);

            senderClient.disconnect();
            if (receiverWS != null) {
                receiverWS.close(1000, "Test done");
            }

            if (!received) {
                log.warn("  Message sent but NOT received by listener (relay might have dropped it)");
                return false;
            }

            // Verify content matches
            if (receivedContent[0] == null) {
                log.warn("  Message received but content is null");
                return false;
            }

            if (!receivedContent[0].equals(originalContent)) {
                log.warn("  Message received but content doesn't match (got {} bytes)", receivedContent[0].length());
                return false;
            }

            log.info("  ✅ Verified: Message received and content matches!");
            return true;

        } catch (Exception e) {
            if (senderClient != null) {
                senderClient.disconnect();
            }
            if (receiverWS != null) {
                receiverWS.close(1000, "Error");
            }

            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("message too large")) {
                log.info("  Relay rejected: message too large");
            } else if (e.getCause() != null && e.getCause().getMessage() != null &&
                       e.getCause().getMessage().contains("message too large")) {
                log.info("  Relay rejected: message too large");
            } else {
                log.warn("  Failed with error: {}", errorMsg);
            }
            return false;
        }
    }

    private WebSocket connectReceiver(NostrKeyManager recipientKeyManager, CountDownLatch receivedLatch, String[] receivedContent) {
        Request request = new Request.Builder().url(RELAY_URL).build();

        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                try {
                    // Subscribe to TOKEN_TRANSFER events for this pubkey
                    String subscriptionId = "test-sub-" + System.currentTimeMillis();
                    Map<String, Object> filter = new HashMap<>();
                    filter.put("kinds", Arrays.asList(KIND_TOKEN_TRANSFER));
                    filter.put("#p", Arrays.asList(recipientKeyManager.getPublicKeyHex()));

                    List<Object> subRequest = Arrays.asList("REQ", subscriptionId, filter);
                    String json = jsonMapper.writeValueAsString(subRequest);
                    webSocket.send(json);
                } catch (Exception e) {
                    log.error("Failed to subscribe", e);
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    List<?> message = jsonMapper.readValue(text, List.class);
                    String messageType = (String) message.get(0);

                    if ("EVENT".equals(messageType)) {
                        JsonNode event = jsonMapper.convertValue(message.get(2), JsonNode.class);
                        String encryptedContent = event.get("content").asText();
                        String senderPubkey = event.get("pubkey").asText();

                        // Decrypt the message using recipient's key manager
                        String decrypted = recipientKeyManager.decryptHex(encryptedContent, senderPubkey);

                        // Remove "token_transfer:" prefix that SDK adds
                        if (decrypted.startsWith("token_transfer:")) {
                            receivedContent[0] = decrypted.substring("token_transfer:".length());
                        } else {
                            receivedContent[0] = decrypted;
                        }

                        receivedLatch.countDown();
                    }
                } catch (Exception e) {
                    log.error("Failed to process message", e);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                log.error("Receiver WebSocket failed", t);
                receivedLatch.countDown();
            }
        };

        return httpClient.newWebSocket(request, listener);
    }

    /**
     * Generate JSON-like content that compresses well (similar to real token data)
     */
    private String generateJsonLikeContent(int sizeBytes) {
        Random random = new Random();
        StringBuilder sb = new StringBuilder(sizeBytes);

        // Generate JSON-like structure with repeated patterns (compresses well)
        String[] keys = {"id", "data", "proof", "signature", "hash", "address", "type", "state", "predicate"};
        String[] values = {"0123456789abcdef", "transaction", "merkle", "schnorr", "sha256"};

        sb.append("{");
        while (sb.length() < sizeBytes - 100) {
            String key = keys[random.nextInt(keys.length)];
            String value = values[random.nextInt(values.length)];

            sb.append("\"").append(key).append("\":\"").append(value)
              .append(random.nextInt(1000000)).append("\",");
        }
        sb.append("\"end\":\"data\"}");

        return sb.toString();
    }
}
