package org.unicitylabs.faucet;

import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.address.DirectAddress;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.JsonRpcAggregatorClient;
import org.unicitylabs.sdk.api.SubmitCommitmentResponse;
import org.unicitylabs.sdk.api.SubmitCommitmentStatus;
import org.unicitylabs.sdk.bft.RootTrustBase;
import org.unicitylabs.sdk.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicateReference;
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicateReference;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenState;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.transaction.MintCommitment;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.transaction.MintTransactionReason;
import org.unicitylabs.sdk.util.InclusionProofUtils;
import org.apache.commons.codec.binary.Hex;

import java.io.InputStream;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;

/**
 * Service for minting nametag tokens for testing
 */
public class NametagMinter {

    private final StateTransitionClient client;
    private final RootTrustBase trustBase;
    private final SecureRandom random;

    /**
     * Create NametagMinter with API key from environment variable AGGREGATOR_API_KEY
     */
    public NametagMinter(String aggregatorUrl) {
        this(aggregatorUrl, getApiKeyFromEnv());
    }

    /**
     * Create NametagMinter with explicit API key
     */
    public NametagMinter(String aggregatorUrl, String apiKey) {
        this.random = new SecureRandom();

        // Load trust base from testnet config
        try {
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("trustbase-testnet.json");
            if (inputStream == null) {
                throw new RuntimeException("trustbase-testnet.json not found in test resources");
            }
            String json = new String(inputStream.readAllBytes());
            this.trustBase = UnicityObjectMapper.JSON.readValue(json, RootTrustBase.class);
            System.out.println("✅ Loaded RootTrustBase from trustbase-testnet.json");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load trustbase-testnet.json", e);
        }

        // Initialize aggregator client
        AggregatorClient aggregatorClient = new JsonRpcAggregatorClient(aggregatorUrl, apiKey);
        this.client = new StateTransitionClient(aggregatorClient);
    }

    private static String getApiKeyFromEnv() {
        try {
            return FaucetConfig.load().getAggregatorApiKey();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load API key: " + e.getMessage(), e);
        }
    }

    /**
     * Mint a nametag for a user
     *
     * @param nametag The nametag string (e.g., "alice-test-12345")
     * @param ownerPrivateKey Owner's private key
     * @param nostrPubKey Owner's Nostr public key (hex)
     * @return Minted nametag token
     */
    public CompletableFuture<Token<MintTransactionReason>> mintNametag(
        String nametag,
        byte[] ownerPrivateKey,
        String nostrPubKey
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("🏷️  Minting nametag: " + nametag);

                // Create owner's signing service (deterministic, no masking)
                SigningService ownerSigningService = SigningService.createFromSecret(ownerPrivateKey);

                // Use FIXED token type (same as fungible tokens) - CRITICAL for address matching!
                // The nametag's owner address must be created with the same TokenType as fungible tokens
                // Otherwise when Alice receives a fungible token, the address won't match
                String TOKEN_TYPE_HEX = "f8aa13834268d29355ff12183066f0cb902003629bbc5eb9ef0efbe397867509";
                byte[] tokenTypeData = Hex.decodeHex(TOKEN_TYPE_HEX);
                TokenType tokenType = new TokenType(tokenTypeData);

                // Create nametag's own address (with random nonce for nametag token)
                byte[] nametagNonce = new byte[32];
                random.nextBytes(nametagNonce);
                SigningService nametagSigningService = SigningService.createFromMaskedSecret(ownerPrivateKey, nametagNonce);
                DirectAddress nametagAddress = MaskedPredicateReference.create(
                    tokenType,
                    nametagSigningService,
                    HashAlgorithm.SHA256,
                    nametagNonce
                ).toAddress();

                // Create owner's target address (deterministic, for receiving tokens)
                DirectAddress ownerAddress = UnmaskedPredicateReference.create(
                    tokenType,
                    ownerSigningService,
                    HashAlgorithm.SHA256
                ).toAddress();

                // Create salt
                byte[] salt = new byte[32];
                random.nextBytes(salt);

                // Create metadata binding Nostr pubkey to this nametag
                var metadata = new java.util.HashMap<String, Object>();
                metadata.put("nostrPubkey", nostrPubKey);
                metadata.put("nametag", nametag);
                metadata.put("version", "1.0");
                byte[] tokenData = UnicityObjectMapper.JSON.writeValueAsBytes(metadata);

                // Create nametag with standard constructor (no tokenData in simplified version)
                // For now, using standard constructor - in production, would need custom implementation
                MintTransaction.NametagData nametagData = new MintTransaction.NametagData(
                    nametag,        // nametag string
                    tokenType,      // token type
                    nametagAddress, // nametag address (random masked address for nametag token itself)
                    salt,           // salt
                    ownerAddress    // owner address (deterministic unmasked address for receiving tokens)
                );

                // TODO: In production, extend MintTransaction.NametagData to include tokenData
                // or use a registry service for Nostr<->Nametag binding

                // Create mint commitment
                MintCommitment<MintTransactionReason> commitment =
                    MintCommitment.create(nametagData);

                // Submit commitment
                System.out.println("📡 Submitting nametag commitment...");
                SubmitCommitmentResponse response = client.submitCommitment(commitment).join();

                if (response.getStatus() != SubmitCommitmentStatus.SUCCESS) {
                    throw new RuntimeException("Failed to submit nametag: " + response.getStatus());
                }

                System.out.println("✅ Nametag commitment submitted!");

                // Wait for inclusion proof
                System.out.println("⏳ Waiting for inclusion proof...");
                var inclusionProof = InclusionProofUtils.waitInclusionProof(
                    client,
                    trustBase,
                    commitment
                ).join();

                if (inclusionProof == null) {
                    throw new RuntimeException("Failed to get inclusion proof for nametag");
                }

                System.out.println("✅ Inclusion proof received!");

                // Create token using Token.create() with proper predicate
                // Use the nametagSigningService (with nonce) for the nametag token's predicate
                Token<MintTransactionReason> token = Token.create(
                    trustBase,
                    new TokenState(
                        MaskedPredicate.create(
                            commitment.getTransactionData().getTokenId(),
                            commitment.getTransactionData().getTokenType(),
                            nametagSigningService,
                            HashAlgorithm.SHA256,
                            nametagNonce
                        ),
                        null
                    ),
                    commitment.toTransaction(inclusionProof)
                );

                System.out.println("✅ Nametag minted successfully: " + nametag);

                return token;
            } catch (Exception e) {
                System.err.println("❌ Error minting nametag: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to mint nametag", e);
            }
        });
    }

}

