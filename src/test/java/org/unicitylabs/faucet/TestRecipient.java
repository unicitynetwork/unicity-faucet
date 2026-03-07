package org.unicitylabs.faucet;

import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.bft.RootTrustBase;
import org.unicitylabs.sdk.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate;
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicate;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenState;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.transaction.TransferTransaction;
import org.apache.commons.codec.binary.Hex;

import java.security.SecureRandom;

/**
 * Test helper to simulate recipient operations (Alice finalizing tokens).
 * This is ONLY for testing - real recipients would do this themselves.
 */
public class TestRecipient {
    private final StateTransitionClient client;
    private final RootTrustBase trustBase;
    private final byte[] privateKey;
    private final SigningService signingService;

    public TestRecipient(StateTransitionClient client, RootTrustBase trustBase, byte[] privateKey) {
        this.client = client;
        this.trustBase = trustBase;
        this.privateKey = privateKey;
        this.signingService = SigningService.createFromSecret(privateKey);
    }

    /**
     * Finalize a received token (what Alice would do on her device)
     * @param nametagToken Alice's nametag token (needed for proxy address resolution)
     */
    public Token<?> finalizeReceivedToken(TokenMinter.TransferInfo transferInfo, Token<?> nametagToken) throws Exception {
        Token<?> sourceToken = transferInfo.getSourceToken();
        Transaction<TransferTransaction.Data> transferTx = transferInfo.getTransferTransaction();

        System.out.println("Finalizing received token...");
        System.out.println("  Source token ID: " + Hex.encodeHexString(sourceToken.getId().getBytes()));
        System.out.println("  Transfer recipient: " + transferTx.getData().getRecipient().getAddress());

        // Get token ID and type from source
        MaskedPredicate sourcePredicate = (MaskedPredicate) sourceToken.getState().getPredicate();

        // Get the nametag token type (all tokens in test use same type)
        org.unicitylabs.sdk.token.TokenType nametagTokenType = nametagToken != null ?
            nametagToken.getType() : sourcePredicate.getTokenType();

        System.out.println("  Source token type: " + Hex.encodeHexString(sourcePredicate.getTokenType().getBytes()).substring(0, 16) + "...");
        System.out.println("  Nametag token type: " + Hex.encodeHexString(nametagTokenType.getBytes()).substring(0, 16) + "...");

        // Create recipient's predicate using the salt from transfer
        // Use UnmaskedPredicate for received transfers
        UnmaskedPredicate recipientPredicate = UnmaskedPredicate.create(
            sourcePredicate.getTokenId(),
            sourcePredicate.getTokenType(),
            signingService,
            HashAlgorithm.SHA256,
            transferTx.getData().getSalt()
        );

        System.out.println("  Recipient predicate created (Unmasked)");
        System.out.println("  Recipient public key: " + Hex.encodeHexString(recipientPredicate.getPublicKey()).substring(0, 16) + "...");

        // Check what address the recipient predicate creates
        String recipientAddress = recipientPredicate.getReference().toAddress().getAddress();
        System.out.println("  Recipient predicate address: " + recipientAddress);
        System.out.println("  Transfer recipient (expected): " + transferTx.getData().getRecipient().getAddress());

        if (nametagToken != null) {
            System.out.println("  Nametag token ID: " + Hex.encodeHexString(nametagToken.getId().getBytes()).substring(0, 32) + "...");
            System.out.println("  Nametag proxy: " + org.unicitylabs.sdk.address.ProxyAddress.create(nametagToken.getId()).getAddress());

            // Get target address from nametag token
            var nametagGenesisTx = (org.unicitylabs.sdk.transaction.MintTransaction<?>) nametagToken.getGenesis();
            nametagGenesisTx.getData().getTokenData().ifPresent(tokenData -> {
                String targetAddr = new String(tokenData);
                System.out.println("  Nametag target address: " + targetAddr);
                System.out.println("  Match? " + targetAddr.equals(recipientAddress));
            });
        }

        try {
            // Finalize the transaction
            // Cast to TransferTransaction (SDK expects concrete type)
            // Include nametag token for proxy address resolution
            java.util.List<Token<?>> nametagTokens = (nametagToken != null) ?
                java.util.List.of(nametagToken) : java.util.List.of();

            Token<?> finalizedToken = client.finalizeTransaction(
                trustBase,
                sourceToken,
                new TokenState(recipientPredicate, null),
                (TransferTransaction) transferTx,
                nametagTokens  // Include nametag for proxy resolution
            );
            System.out.println("✅ Token finalized successfully");
            return finalizedToken;
        } catch (org.unicitylabs.sdk.verification.VerificationException ve) {
            System.err.println("❌ Verification failed!");
            System.err.println("  Verification result: " + ve.getVerificationResult());
            System.err.println("  Details: " + ve.getMessage());
            throw ve;
        }
    }

}
