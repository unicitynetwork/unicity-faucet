package org.unicitylabs.faucet;

import org.apache.commons.codec.binary.Hex;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.SubmitCommitmentResponse;
import org.unicitylabs.sdk.api.SubmitCommitmentStatus;
import org.unicitylabs.sdk.bft.RootTrustBase;
import org.unicitylabs.sdk.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate;
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicate;
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicateReference;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenId;
import org.unicitylabs.sdk.token.TokenState;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.token.fungible.CoinId;
import org.unicitylabs.sdk.token.fungible.TokenCoinData;
import org.unicitylabs.sdk.transaction.MintCommitment;
import org.unicitylabs.sdk.transaction.split.SplitMintReason;
import org.unicitylabs.sdk.transaction.split.TokenSplitBuilder;
import org.unicitylabs.sdk.util.InclusionProofUtils;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Helper class for testing token splits
 */
public class TokenSplitter {

    private final StateTransitionClient client;
    private final RootTrustBase trustBase;
    private final byte[] ownerSecret;
    private final SecureRandom random;

    public TokenSplitter(StateTransitionClient client, RootTrustBase trustBase, byte[] ownerSecret) {
        this.client = client;
        this.trustBase = trustBase;
        this.ownerSecret = ownerSecret;
        this.random = new SecureRandom();
    }

    public static class SplitResult {
        public final List<Token<?>> splitTokens;
        public final Token<?> burnedToken;

        public SplitResult(List<Token<?>> splitTokens, Token<?> burnedToken) {
            this.splitTokens = splitTokens;
            this.burnedToken = burnedToken;
        }
    }

    /**
     * Split a token into two tokens with specified amounts
     */
    public SplitResult splitToken(
        Token<?> tokenToSplit,
        BigInteger amount1,
        BigInteger amount2,
        Token<?> nametagToken
    ) throws Exception {

        System.out.println("🔪 Splitting token: " + Hex.encodeHexString(tokenToSplit.getId().getBytes()).substring(0, 8) + "...");
        System.out.println("   Amounts: " + amount1 + " + " + amount2);

        // Extract coin ID from original token
        if (!tokenToSplit.getCoins().isPresent()) {
            throw new Exception("Token has no coins");
        }

        TokenCoinData originalCoins = tokenToSplit.getCoins().get();
        Map.Entry<CoinId, BigInteger> firstCoin = originalCoins.getCoins().entrySet().iterator().next();
        CoinId coinId = firstCoin.getKey();
        TokenType tokenType = tokenToSplit.getType();

        // Build the split
        TokenSplitBuilder builder = new TokenSplitBuilder();

        // Generate deterministic IDs for retry safety
        String seed = Hex.encodeHexString(tokenToSplit.getId().getBytes()) + "_" + amount1 + "_" + amount2;
        byte[] hash1 = MessageDigest.getInstance("SHA-256").digest(seed.getBytes());
        byte[] hash2 = MessageDigest.getInstance("SHA-256").digest((seed + "_2").getBytes());

        TokenId token1Id = new TokenId(copyBytes(hash1, 32));
        TokenId token2Id = new TokenId(copyBytes(hash2, 32));

        byte[] salt1 = MessageDigest.getInstance("SHA-256").digest((seed + "_salt1").getBytes());
        byte[] salt2 = MessageDigest.getInstance("SHA-256").digest((seed + "_salt2").getBytes());

        // Create signing service for the owner
        SigningService ownerSigning = SigningService.createFromSecret(ownerSecret);

        // Get owner's address
        var ownerAddress = org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicateReference.create(
            tokenType,
            ownerSigning,
            HashAlgorithm.SHA256
        ).toAddress();

        // Create both split tokens with owner's address
        builder.createToken(
            token1Id,
            tokenType,
            null,
            new TokenCoinData(Map.of(coinId, amount1)),
            ownerAddress,
            salt1,
            null
        );

        builder.createToken(
            token2Id,
            tokenType,
            null,
            new TokenCoinData(Map.of(coinId, amount2)),
            ownerAddress,
            salt2,
            null
        );

        TokenSplitBuilder.TokenSplit split = builder.build(tokenToSplit);

        // Create burn commitment
        byte[] burnSalt = MessageDigest.getInstance("SHA-256").digest((seed + "_burn").getBytes());

        // For burning: Use the same signing service that created the token
        // The SDK handles the nonce internally when validating the burn
        SigningService burnSigning = ownerSigning;

        var burnCommitment = split.createBurnCommitment(burnSalt, burnSigning);

        System.out.println("🔥 Burning original token...");
        SubmitCommitmentResponse burnResponse = client.submitCommitment(burnCommitment).get();

        if (burnResponse.getStatus() == SubmitCommitmentStatus.REQUEST_ID_EXISTS) {
            System.out.println("⚠️  Token already burned - recovering...");
        } else if (burnResponse.getStatus() != SubmitCommitmentStatus.SUCCESS) {
            throw new Exception("Failed to burn token: " + burnResponse.getStatus());
        }

        var burnProof = InclusionProofUtils.waitInclusionProof(client, trustBase, burnCommitment).get();
        var burnTransaction = burnCommitment.toTransaction(burnProof);

        System.out.println("✅ Token burned");

        // Create mint commitments for split tokens
        var splitMintCommitments = split.createSplitMintCommitments(trustBase, burnTransaction);
        System.out.println("💎 Minting " + splitMintCommitments.size() + " split tokens...");

        List<Token<?>> splitTokens = new ArrayList<>();

        for (int i = 0; i < splitMintCommitments.size(); i++) {
            var commitment = splitMintCommitments.get(i);

            var response = client.submitCommitment(commitment).get();

            if (response.getStatus() == SubmitCommitmentStatus.REQUEST_ID_EXISTS) {
                System.out.println("⚠️  Split token " + i + " already minted - recovering...");
            } else if (response.getStatus() != SubmitCommitmentStatus.SUCCESS) {
                throw new Exception("Failed to mint split token " + i + ": " + response.getStatus());
            }

            var proof = InclusionProofUtils.waitInclusionProof(client, trustBase, commitment).get();

            // Create token state with UnmaskedPredicate - use data from commitment
            var txData = commitment.getTransactionData();
            TokenState state = new TokenState(
                UnmaskedPredicate.create(
                    txData.getTokenId(),
                    txData.getTokenType(),
                    ownerSigning,
                    HashAlgorithm.SHA256,
                    txData.getSalt()
                ),
                null
            );

            Token<?> splitToken = Token.create(
                trustBase,
                state,
                commitment.toTransaction(proof)
            );

            splitTokens.add(splitToken);
            java.math.BigInteger tokenAmount = splitToken.getCoins().get().getCoins().values().iterator().next();
            System.out.println("✅ Split token " + i + " created: " + tokenAmount + " units");
        }

        System.out.println("✅ Split complete!");
        return new SplitResult(splitTokens, tokenToSplit);
    }

    private byte[] copyBytes(byte[] src, int length) {
        byte[] result = new byte[length];
        System.arraycopy(src, 0, result, 0, Math.min(src.length, length));
        return result;
    }
}
