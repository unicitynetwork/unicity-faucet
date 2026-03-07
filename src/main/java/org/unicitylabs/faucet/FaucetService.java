package org.unicitylabs.faucet;

import org.unicitylabs.faucet.db.FaucetDatabase;
import org.unicitylabs.faucet.db.FaucetRequest;
import org.unicitylabs.nostr.client.NostrClient;
import org.unicitylabs.nostr.crypto.NostrKeyManager;
import org.unicitylabs.sdk.address.ProxyAddress;
import org.unicitylabs.sdk.token.TokenId;

import java.io.File;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service for minting and distributing tokens via the faucet
 * Extracted from FaucetCLI for use in REST API
 *
 * Thread-safety: Uses a shared NostrClient for all requests to avoid
 * connection/disconnection races when handling concurrent requests.
 */
public class FaucetService {

    private final FaucetConfig config;
    private final byte[] faucetPrivateKey;
    private final TokenMinter minter;
    private final UnicityTokenRegistry registry;
    private final FaucetDatabase database;
    private final String dataDir;

    // Shared Nostr client for all requests - stays connected
    private final NostrClient sharedNostrClient;
    private final AtomicBoolean nostrConnected = new AtomicBoolean(false);

    public FaucetService(FaucetConfig config, String dataDir) throws Exception {
        this.config = config;
        this.dataDir = dataDir;

        // Ensure data directory exists
        File dir = new File(dataDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Ensure tokens directory exists
        File tokensDir = new File(dataDir + "/tokens");
        if (!tokensDir.exists()) {
            tokensDir.mkdirs();
        }

        // Initialize database
        this.database = new FaucetDatabase(dataDir);

        // Derive private key from mnemonic
        this.faucetPrivateKey = mnemonicToPrivateKey(config.faucetMnemonic);

        // Initialize token minter
        this.minter = new TokenMinter(config.aggregatorUrl, faucetPrivateKey, config.getAggregatorApiKey());

        // Load token registry
        this.registry = UnicityTokenRegistry.getInstance(config.registryUrl);

        // Initialize shared Nostr client (stays connected for all requests)
        NostrKeyManager keyManager = NostrKeyManager.fromPrivateKey(faucetPrivateKey);
        this.sharedNostrClient = new NostrClient(keyManager);
        this.sharedNostrClient.setAutoReconnect(true);
        this.sharedNostrClient.setQueryTimeoutMs(10000); // 10 second timeout for nametag queries

        // Add connection listener for logging
        this.sharedNostrClient.addConnectionListener(new NostrClient.ConnectionEventListener() {
            @Override
            public void onConnect(String relayUrl) {
                System.out.println("✅ Nostr connected to: " + relayUrl);
                nostrConnected.set(true);
            }

            @Override
            public void onDisconnect(String relayUrl, String reason) {
                System.out.println("⚠️ Nostr disconnected from " + relayUrl + ": " + reason);
                nostrConnected.set(false);
            }

            @Override
            public void onReconnecting(String relayUrl, int attempt) {
                System.out.println("🔄 Nostr reconnecting to " + relayUrl + " (attempt " + attempt + ")...");
            }

            @Override
            public void onReconnected(String relayUrl) {
                System.out.println("✅ Nostr reconnected to: " + relayUrl);
                nostrConnected.set(true);
            }
        });

        // Connect to relay at startup
        System.out.println("🔌 Connecting to Nostr relay: " + config.nostrRelay);
        this.sharedNostrClient.connect(config.nostrRelay).join();
    }

    /**
     * Process a faucet request: mint tokens and send to recipient
     *
     * @param unicityId The recipient's Unicity ID (nametag)
     * @param coinName The coin name (e.g., "solana", "bitcoin")
     * @param amount The amount in user-friendly units (e.g., 0.05 SOL)
     * @return CompletableFuture with the request result
     */
    public CompletableFuture<FaucetRequestResult> processFaucetRequest(
            String unicityId, String coinName, double amount) {

        return CompletableFuture.supplyAsync(() -> {
            FaucetRequest request = null;
            try {
                // Get token type from registry
                String tokenTypeHex = registry.getUnicityTokenType();
                if (tokenTypeHex == null) {
                    throw new RuntimeException("Could not find Unicity token type in registry");
                }

                // Find the coin definition
                UnicityTokenRegistry.CoinDefinition coinDef = registry.getCoinByName(coinName);
                if (coinDef == null) {
                    throw new RuntimeException("Coin not found: " + coinName);
                }

                // Validate coin ID
                if (coinDef.id.equals(tokenTypeHex)) {
                    throw new RuntimeException("Invalid registry data: Coin ID equals Token Type");
                }

                // Calculate amount in smallest units
                int decimals = coinDef.decimals != null ? coinDef.decimals : 8;
                BigDecimal userAmountDecimal = BigDecimal.valueOf(amount);
                BigDecimal multiplier = BigDecimal.TEN.pow(decimals);
                BigDecimal tokenAmountBD = userAmountDecimal.multiply(multiplier);
                BigInteger tokenAmount = tokenAmountBD.toBigInteger();

                // Validate amount
                if (tokenAmount.compareTo(BigInteger.ZERO) <= 0) {
                    throw new RuntimeException("Invalid amount: " + amount + " " + coinDef.symbol);
                }

                // Create database record
                request = new FaucetRequest(
                        unicityId,
                        coinDef.symbol,
                        coinDef.name,
                        coinDef.id,
                        amount,
                        tokenAmount.toString(),
                        null  // Will be set after nametag resolution
                );
                long requestId = database.insertRequest(request);

                // Step 1: Resolve nametag to Nostr public key using shared client
                String recipientPubKey;
                try {
                    System.out.println("🔍 Resolving nametag via Nostr relay: " + unicityId);
                    recipientPubKey = sharedNostrClient.queryPubkeyByNametag(unicityId).join();
                    if (recipientPubKey == null) {
                        throw new RuntimeException("Nametag not found: " + unicityId);
                    }
                    System.out.println("✅ Resolved nametag '" + unicityId + "' to: " + recipientPubKey.substring(0, 16) + "...");
                    request.setRecipientNostrPubkey(recipientPubKey);
                    database.updateRequest(request);
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    String errorMsg = (cause != null) ? cause.getMessage() : e.getMessage();
                    throw new RuntimeException("Failed to resolve nametag: " + errorMsg);
                }

                // Step 2: Mint token
                var mintedToken = minter.mintToken(tokenTypeHex, coinDef.id, tokenAmount).join();

                // Step 3: Create ProxyAddress from nametag
                TokenId nametagTokenId = TokenId.fromNameTag(unicityId);
                ProxyAddress recipientProxyAddress = ProxyAddress.create(nametagTokenId);

                // Step 4: Transfer token to proxy address
                TokenMinter.TransferInfo transferInfo = minter.transferToProxyAddress(
                        mintedToken,
                        recipientProxyAddress
                ).join();

                // Step 5: Serialize token and transaction
                String sourceTokenJson = minter.serializeToken(transferInfo.getSourceToken());
                String transferTxJson = minter.serializeTransaction(transferInfo.getTransferTransaction());

                // Step 6: Save token files
                String tokenFileName = String.format("token_%d_%s_%s.json",
                        requestId, unicityId, System.currentTimeMillis());
                String tokenFilePath = dataDir + "/tokens/" + tokenFileName;

                Map<String, String> tokenData = new HashMap<>();
                tokenData.put("sourceToken", sourceTokenJson);
                tokenData.put("transferTx", transferTxJson);

                try (FileWriter writer = new FileWriter(tokenFilePath)) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    mapper.writerWithDefaultPrettyPrinter().writeValue(writer, tokenData);
                }

                request.setTokenFilePath(tokenFilePath);
                database.updateRequest(request);

                // Step 7: Create transfer package
                String transferPackage = createTransferPackage(sourceTokenJson, transferTxJson);

                // Step 8: Send via Nostr using shared client (no connect/disconnect per request)
                System.out.println("📤 Sending token transfer to " + recipientPubKey.substring(0, 16) + "...");
                sharedNostrClient.sendTokenTransfer(recipientPubKey, transferPackage).join();
                System.out.println("✅ Token transfer sent successfully");

                // Update status to SUCCESS
                request.setStatus("SUCCESS");
                database.updateRequest(request);

                return new FaucetRequestResult(
                        true,
                        "Token sent successfully",
                        requestId,
                        unicityId,
                        coinDef.name,
                        coinDef.symbol,
                        amount,
                        tokenAmount.toString(),
                        recipientPubKey,
                        tokenFilePath
                );

            } catch (Exception e) {
                // Extract clean error message
                String cleanErrorMsg = extractErrorMessage(e);

                // Update status to FAILED
                if (request != null) {
                    request.setStatus("FAILED");
                    request.setErrorMessage(cleanErrorMsg);
                    try {
                        database.updateRequest(request);
                    } catch (Exception dbEx) {
                        System.err.println("Failed to update request status: " + dbEx.getMessage());
                    }
                }

                return new FaucetRequestResult(
                        false,
                        cleanErrorMsg,
                        request != null ? request.getId() : null,
                        unicityId,
                        coinName,
                        null,
                        amount,
                        null,
                        null,
                        null
                );
            }
        });
    }

    /**
     * Extract a clean, user-friendly error message from an exception
     * Removes Java exception class names and stack traces
     */
    private static String extractErrorMessage(Exception e) {
        String msg = e.getMessage();

        // Unwrap CompletionException and ExecutionException
        Throwable current = e;
        while (current.getCause() != null &&
               (current instanceof java.util.concurrent.CompletionException ||
                current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
            if (current.getMessage() != null) {
                msg = current.getMessage();
            }
        }

        if (msg == null) {
            msg = "Unknown error occurred";
        }

        // Remove "java.lang.RuntimeException: " and similar prefixes
        msg = msg.replaceAll("^java\\.lang\\.\\w+Exception:\\s*", "");
        msg = msg.replaceAll("^java\\.util\\.concurrent\\.\\w+Exception:\\s*", "");
        msg = msg.replaceAll("^org\\.unicitylabs\\.\\w+\\.\\w+Exception:\\s*", "");

        // Clean up nested exception messages
        msg = msg.replaceAll("java\\.util\\.concurrent\\.CompletionException:\\s*", "");
        msg = msg.replaceAll("java\\.lang\\.RuntimeException:\\s*", "");

        // Remove "Failed to resolve nametag: " prefix if followed by another "Nametag not found"
        msg = msg.replaceAll("Failed to resolve nametag:\\s*", "");

        return msg;
    }

    /**
     * Shutdown the service and clean up resources
     */
    public void shutdown() {
        System.out.println("🔌 Shutting down FaucetService...");
        if (sharedNostrClient != null) {
            sharedNostrClient.disconnect();
        }
    }

    /**
     * Check if Nostr client is connected
     */
    public boolean isNostrConnected() {
        return nostrConnected.get();
    }

    /**
     * Get all supported coins from registry
     */
    public UnicityTokenRegistry.CoinDefinition[] getSupportedCoins() {
        return registry.getFungibleCoins().toArray(new UnicityTokenRegistry.CoinDefinition[0]);
    }

    /**
     * Get database instance for history queries
     */
    public FaucetDatabase getDatabase() {
        return database;
    }

    /**
     * Create transfer package (same as FaucetCLI)
     */
    private String createTransferPackage(String sourceTokenJson, String transferTxJson) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, String> payload = new HashMap<>();
        payload.put("sourceToken", sourceTokenJson);
        payload.put("transferTx", transferTxJson);
        return mapper.writeValueAsString(payload);
    }

    /**
     * Derive private key from BIP-39 mnemonic (same as FaucetCLI)
     */
    private byte[] mnemonicToPrivateKey(String mnemonic) throws Exception {
        byte[] seed = org.bitcoinj.crypto.MnemonicCode.toSeed(
                java.util.Arrays.asList(mnemonic.split(" ")),
                ""
        );
        byte[] privateKey = new byte[32];
        System.arraycopy(seed, 0, privateKey, 0, 32);
        return privateKey;
    }

    /**
     * Result of a faucet request
     */
    public static class FaucetRequestResult {
        public final boolean success;
        public final String message;
        public final Long requestId;
        public final String unicityId;
        public final String coinName;
        public final String coinSymbol;
        public final double amount;
        public final String amountInSmallestUnits;
        public final String recipientNostrPubkey;
        public final String tokenFilePath;

        public FaucetRequestResult(boolean success, String message, Long requestId,
                                   String unicityId, String coinName, String coinSymbol,
                                   double amount, String amountInSmallestUnits,
                                   String recipientNostrPubkey, String tokenFilePath) {
            this.success = success;
            this.message = message;
            this.requestId = requestId;
            this.unicityId = unicityId;
            this.coinName = coinName;
            this.coinSymbol = coinSymbol;
            this.amount = amount;
            this.amountInSmallestUnits = amountInSmallestUnits;
            this.recipientNostrPubkey = recipientNostrPubkey;
            this.tokenFilePath = tokenFilePath;
        }
    }
}
