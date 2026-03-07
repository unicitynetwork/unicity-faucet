package org.unicitylabs.faucet;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

// Nostr SDK imports
import org.unicitylabs.nostr.client.NostrClient;
import org.unicitylabs.nostr.crypto.NostrKeyManager;
import org.unicitylabs.nostr.token.TokenTransferProtocol;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * CLI application for minting Unicity tokens and sending them via Nostr to a nametag recipient
 *
 * Usage:
 *   ./gradlew run --args="--nametag=alice --amount=100"
 *   ./gradlew mint --args="--nametag=alice --amount=100"
 */
@Command(
    name = "faucet",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    description = "Mint Unicity tokens and send them via Nostr to a nametag recipient"
)
public class FaucetCLI implements Callable<Integer> {

    @Option(
        names = {"-n", "--nametag"},
        required = true,
        description = "Recipient's nametag (e.g., 'alice')"
    )
    private String nametag;

    @Option(
        names = {"-a", "--amount"},
        description = "Token amount in human-readable units (e.g., 0.05 for SOL, uses default from config if not specified)"
    )
    private Double amount;

    @Option(
        names = {"-c", "--coin"},
        description = "Coin to mint (e.g., 'solana', 'bitcoin', 'ethereum', 'tether', 'usd-coin', uses default from config if not specified)"
    )
    private String coin;

    @Option(
        names = {"--refresh"},
        description = "Force refresh registry from GitHub (ignores cache)"
    )
    private boolean refresh;

    @Option(
        names = {"--config"},
        description = "Path to config file (default: faucet-config.json in resources)"
    )
    private String configPath;

    @Override
    public Integer call() throws Exception {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║   Unicity Token Faucet v1.0.0        ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println();

        // Load configuration
        FaucetConfig config = FaucetConfig.load();
        System.out.println("✅ Configuration loaded");
        System.out.println("   Relay: " + config.nostrRelay);
        System.out.println("   Aggregator: " + config.aggregatorUrl);
        System.out.println("   Registry: " + config.registryUrl);
        System.out.println();

        // Clear cache if refresh flag is set
        if (refresh) {
            System.out.println("🔄 Refresh flag set - clearing registry cache...");
            UnicityTokenRegistry.clearCache();
        }

        // Load token registry from configured URL
        UnicityTokenRegistry registry = UnicityTokenRegistry.getInstance(config.registryUrl);

        // Get token type from registry (non-fungible "unicity" asset)
        String tokenTypeHex = registry.getUnicityTokenType();
        if (tokenTypeHex == null) {
            System.err.println("\n❌ Could not find Unicity token type in registry");
            System.exit(1);
            return 1;
        }
        System.out.println("📝 Token Type: " + tokenTypeHex);
        System.out.println();

        // Determine which coin to mint
        String coinName = (coin != null) ? coin : config.defaultCoin;

        // Find the coin definition by name
        UnicityTokenRegistry.CoinDefinition coinDef = registry.getCoinByName(coinName);
        if (coinDef == null) {
            System.err.println("\n❌ Coin not found: " + coinName);
            System.err.println("\n📋 Available coins:");
            for (UnicityTokenRegistry.CoinDefinition c : registry.getFungibleCoins()) {
                System.err.println("   - " + c.name + " (" + c.symbol + ") - " + c.decimals + " decimals");
            }
            System.exit(1);
            return 1;
        }

        System.out.println("💎 Coin: " + coinDef.name + " (" + coinDef.symbol + ")");
        System.out.println("   Decimals: " + coinDef.decimals);
        System.out.println();

        // Validate coin ID is different from token type
        if (coinDef.id.equals(tokenTypeHex)) {
            System.err.println("\n❌ Invalid registry data: Coin ID equals Token Type");
            System.err.println("   This indicates the registry entry is incorrectly configured.");
            System.err.println("   Coin '" + coinName + "' should have a unique coin ID, not the token type ID.");
            System.exit(1);
            return 1;
        }

        // Determine user-friendly amount
        double userAmount = (amount != null) ? amount : config.defaultAmount;
        int decimals = coinDef.decimals != null ? coinDef.decimals : 8;

        // Convert user amount to smallest units using BigDecimal for precision
        // This avoids floating point errors (e.g., 0.0003 * 10^8 = 30000, not 29999)
        java.math.BigDecimal userAmountDecimal = java.math.BigDecimal.valueOf(userAmount);
        java.math.BigDecimal multiplier = java.math.BigDecimal.TEN.pow(decimals);
        java.math.BigDecimal tokenAmountBD = userAmountDecimal.multiply(multiplier);

        // Convert to BigInteger (supports arbitrary precision)
        java.math.BigInteger tokenAmount = tokenAmountBD.toBigInteger();

        // Validate amount is not zero or negative
        if (tokenAmount.compareTo(java.math.BigInteger.ZERO) <= 0) {
            System.err.println("\n❌ Invalid amount: " + userAmount + " " + coinDef.symbol);
            System.err.println("   After applying " + decimals + " decimals, the amount becomes " + tokenAmount);
            System.err.println("   Minimum amount for " + coinDef.symbol + ": " +
                java.math.BigDecimal.ONE.divide(multiplier) + " " + coinDef.symbol);
            System.exit(1);
            return 1;
        }

        System.out.println("💰 Minting tokens:");
        System.out.println("   User amount: " + userAmount);
        System.out.println("   Decimals: " + decimals);
        System.out.println("   Actual amount (smallest units): " + tokenAmount);
        System.out.println();

        // Derive private key from mnemonic
        byte[] faucetPrivateKey = mnemonicToPrivateKey(config.faucetMnemonic);

        // Step 1: Resolve nametag to Nostr public key
        NametagResolver nametagResolver = new NametagResolver(config.nostrRelay, faucetPrivateKey);
        String recipientPubKey;
        try {
            recipientPubKey = nametagResolver.resolveNametag(nametag).join();
        } catch (Exception e) {
            Throwable cause = e.getCause();
            String errorMsg = (cause != null) ? cause.getMessage() : e.getMessage();
            System.err.println("\n❌ " + errorMsg);
            System.err.println("\nMake sure the wallet user has minted the nametag and published the binding to Nostr.");
            System.exit(1);
            return 1;
        }

        // Step 2: Mint token to faucet
        System.out.println();
        System.out.println("🔨 Minting " + userAmount + " " + coinDef.symbol + "...");
        TokenMinter minter = new TokenMinter(config.aggregatorUrl, faucetPrivateKey, config.getAggregatorApiKey());
        var mintedToken = minter.mintToken(
            tokenTypeHex,  // Token type from registry
            coinDef.id,    // Coin ID from registry
            tokenAmount
        ).join();

        // Step 3: Create ProxyAddress from nametag (deterministic from nametag string)
        System.out.println();
        System.out.println("🔍 Creating proxy address from nametag...");

        org.unicitylabs.sdk.token.TokenId nametagTokenId = org.unicitylabs.sdk.token.TokenId.fromNameTag(nametag);
        org.unicitylabs.sdk.address.ProxyAddress recipientProxyAddress = org.unicitylabs.sdk.address.ProxyAddress.create(nametagTokenId);

        System.out.println("✅ Proxy address: " + recipientProxyAddress.getAddress());

        // Step 4: Transfer token to the proxy address
        TokenMinter.TransferInfo transferInfo = minter.transferToProxyAddress(
            mintedToken,
            recipientProxyAddress
        ).join();

        // Step 5: Serialize both source token and transfer transaction
        String sourceTokenJson = minter.serializeToken(transferInfo.getSourceToken());
        String transferTxJson = minter.serializeTransaction(transferInfo.getTransferTransaction());

        // Step 6: Create transfer package
        String transferPackage = createTransferPackage(sourceTokenJson, transferTxJson);

        // Step 7: Send via Nostr to recipient's Nostr pubkey
        System.out.println();
        System.out.println("📨 Sending token transfer package to " + nametag + " via Nostr...");
        System.out.println("   Nostr pubkey: " + recipientPubKey.substring(0, 16) + "...");
        System.out.println("   Proxy address: " + recipientProxyAddress.getAddress());

        // Send token transfer using SDK's TokenTransferProtocol
        // This ensures correct event kind (31113) and proper formatting
        NostrKeyManager keyManager = NostrKeyManager.fromPrivateKey(faucetPrivateKey);
        NostrClient nostrClient = new NostrClient(keyManager);
        nostrClient.connect(config.nostrRelay).join();

        // Use SDK method to create properly formatted TOKEN_TRANSFER event
        nostrClient.sendTokenTransfer(recipientPubKey, transferPackage).join();

        nostrClient.disconnect();

        System.out.println();
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║  ✅ Token sent successfully!         ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println();
        System.out.println("📊 Summary:");
        System.out.println("   Recipient: " + nametag);
        System.out.println("   Coin: " + coinDef.name + " (" + coinDef.symbol + ")");
        System.out.println("   Amount: " + userAmount + " " + coinDef.symbol);
        System.out.println("   Smallest units: " + tokenAmount);
        System.out.println("   Delivery: Nostr relay");
        System.out.println();

        // Clean shutdown
        System.exit(0);
        return 0;
    }

    /**
     * Create a transfer package with source token and transfer transaction
     * Format: {"sourceToken":"...","transferTx":"..."}
     * The JSON strings are properly escaped and embedded as string values
     * NOTE: SDK will add "token_transfer:" prefix automatically
     */
    private String createTransferPackage(String sourceTokenJson, String transferTxJson) throws Exception {
        // Escape the JSON strings properly for embedding as string values
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        // Create a map with the JSON strings as values
        java.util.Map<String, String> payload = new java.util.HashMap<>();
        payload.put("sourceToken", sourceTokenJson);
        payload.put("transferTx", transferTxJson);

        // Serialize the map (this will properly escape the JSON string values)
        // DON'T add "token_transfer:" prefix - SDK does that
        return mapper.writeValueAsString(payload);
    }

    /**
     * Derive private key from BIP-39 mnemonic phrase
     */
    private byte[] mnemonicToPrivateKey(String mnemonic) throws Exception {
        // Convert mnemonic to seed using BIP-39 (BitcoinJ)
        byte[] seed = org.bitcoinj.crypto.MnemonicCode.toSeed(
            java.util.Arrays.asList(mnemonic.split(" ")),
            "" // No passphrase
        );

        // Use first 32 bytes of seed as private key
        byte[] privateKey = new byte[32];
        System.arraycopy(seed, 0, privateKey, 0, 32);
        return privateKey;
    }


    public static void main(String[] args) {
        int exitCode = new CommandLine(new FaucetCLI()).execute(args);
        System.exit(exitCode);
    }
}
