package org.unicitylabs.faucet.db;

import java.time.Instant;

/**
 * Represents a faucet request record
 */
public class FaucetRequest {
    private Long id;
    private String unicityId;  // Nametag
    private String coinSymbol;
    private String coinName;
    private String coinId;
    private double amount;
    private String amountInSmallestUnits;
    private String recipientNostrPubkey;
    private String tokenFilePath;
    private String status;  // SUCCESS, FAILED
    private String errorMessage;
    private Instant timestamp;

    public FaucetRequest() {
    }

    public FaucetRequest(String unicityId, String coinSymbol, String coinName, String coinId,
                         double amount, String amountInSmallestUnits, String recipientNostrPubkey) {
        this.unicityId = unicityId;
        this.coinSymbol = coinSymbol;
        this.coinName = coinName;
        this.coinId = coinId;
        this.amount = amount;
        this.amountInSmallestUnits = amountInSmallestUnits;
        this.recipientNostrPubkey = recipientNostrPubkey;
        this.status = "PENDING";
        this.timestamp = Instant.now();
    }

    // Getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUnicityId() {
        return unicityId;
    }

    public void setUnicityId(String unicityId) {
        this.unicityId = unicityId;
    }

    public String getCoinSymbol() {
        return coinSymbol;
    }

    public void setCoinSymbol(String coinSymbol) {
        this.coinSymbol = coinSymbol;
    }

    public String getCoinName() {
        return coinName;
    }

    public void setCoinName(String coinName) {
        this.coinName = coinName;
    }

    public String getCoinId() {
        return coinId;
    }

    public void setCoinId(String coinId) {
        this.coinId = coinId;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public String getAmountInSmallestUnits() {
        return amountInSmallestUnits;
    }

    public void setAmountInSmallestUnits(String amountInSmallestUnits) {
        this.amountInSmallestUnits = amountInSmallestUnits;
    }

    public String getRecipientNostrPubkey() {
        return recipientNostrPubkey;
    }

    public void setRecipientNostrPubkey(String recipientNostrPubkey) {
        this.recipientNostrPubkey = recipientNostrPubkey;
    }

    public String getTokenFilePath() {
        return tokenFilePath;
    }

    public void setTokenFilePath(String tokenFilePath) {
        this.tokenFilePath = tokenFilePath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
