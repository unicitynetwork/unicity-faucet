package org.unicitylabs.faucet;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import org.unicitylabs.faucet.db.FaucetRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API server for the Unicity Token Faucet
 * Provides endpoints for minting and distributing tokens via web interface
 */
public class FaucetServer {

    private final FaucetService faucetService;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final int port;
    private final String aggregatorUrl;

    public FaucetServer(FaucetService faucetService, String apiKey, int port, String aggregatorUrl) {
        this.faucetService = faucetService;
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
        this.port = port;
        this.aggregatorUrl = aggregatorUrl;
    }

    /**
     * Start the server
     */
    public void start() {
        Javalin app = Javalin.create(config -> {
            // Serve static files from resources/public
            config.staticFiles.add("/public", Location.CLASSPATH);

            // Enable CORS for development
            config.plugins.enableCors(cors -> {
                cors.add(it -> it.anyHost());
            });
        });

        // API v1 faucet endpoints
        app.get("/api/v1/faucet/coins", this::getCoins);
        app.post("/api/v1/faucet/request", this::submitFaucetRequest);
        app.get("/api/v1/faucet/history", this::getHistory);

        // Health check
        app.get("/health", ctx -> ctx.json(Map.of("status", "healthy")));

        // Admin endpoints
        app.post("/api/v1/admin/refresh-registry", this::refreshRegistry);

        // Start server
        app.start(port);

        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║   Unicity Token Faucet Server v1.0.0             ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("🌐 Server running on http://localhost:" + port);
        System.out.println("🔗 Aggregator URL: " + aggregatorUrl);
        System.out.println();
        System.out.println("📍 Endpoints:");
        System.out.println("   Web UI:        http://localhost:" + port + "/faucet/index.html");
        System.out.println("   History:       http://localhost:" + port + "/faucet/history/index.html");
        System.out.println("   API:           http://localhost:" + port + "/api/v1/faucet/");
        System.out.println();
    }

    /**
     * GET /api/v1/faucet/coins
     * Returns list of supported coins with metadata
     */
    private void getCoins(Context ctx) {
        try {
            var coins = faucetService.getSupportedCoins();

            // Convert to response format with icon URLs
            List<Map<String, Object>> coinList = new java.util.ArrayList<>();
            for (var coin : coins) {
                Map<String, Object> coinData = new HashMap<>();
                coinData.put("id", coin.id);
                coinData.put("name", coin.name);
                coinData.put("symbol", coin.symbol);
                coinData.put("decimals", coin.decimals);
                coinData.put("description", coin.description);
                coinData.put("iconUrl", coin.getIconUrl());
                coinList.add(coinData);
            }

            ctx.json(Map.of(
                    "success", true,
                    "coins", coinList
            ));
        } catch (Exception e) {
            ctx.status(500).json(Map.of(
                    "success", false,
                    "error", "Failed to load coins: " + e.getMessage()
            ));
        }
    }

    /**
     * POST /api/v1/faucet/request
     * Submit a faucet request
     * Request body: {"unicityId": "alice", "coin": "solana", "amount": 0.05}
     */
    private void submitFaucetRequest(Context ctx) {
        System.out.println("\n════════════════════════════════════════════════════");
        System.out.println("🔵 POST /api/v1/faucet/request - Request ID: " + System.currentTimeMillis());
        System.out.println("════════════════════════════════════════════════════\n");

        try {
            // Parse request body
            Map<String, Object> body = ctx.bodyAsClass(Map.class);

            String unicityId = (String) body.get("unicityId");
            String coin = (String) body.get("coin");
            Object amountObj = body.get("amount");

            // Validate inputs
            if (unicityId == null || unicityId.trim().isEmpty()) {
                ctx.status(400).json(Map.of(
                        "success", false,
                        "error", "unicityId is required"
                ));
                return;
            }

            if (coin == null || coin.trim().isEmpty()) {
                ctx.status(400).json(Map.of(
                        "success", false,
                        "error", "coin is required"
                ));
                return;
            }

            if (amountObj == null) {
                ctx.status(400).json(Map.of(
                        "success", false,
                        "error", "amount is required"
                ));
                return;
            }

            // Convert amount to double (handle both Integer and Double from JSON)
            double amount;
            if (amountObj instanceof Integer) {
                amount = ((Integer) amountObj).doubleValue();
            } else if (amountObj instanceof Double) {
                amount = (Double) amountObj;
            } else if (amountObj instanceof String) {
                try {
                    amount = Double.parseDouble((String) amountObj);
                } catch (NumberFormatException e) {
                    ctx.status(400).json(Map.of(
                            "success", false,
                            "error", "Invalid amount format"
                    ));
                    return;
                }
            } else {
                ctx.status(400).json(Map.of(
                        "success", false,
                        "error", "Invalid amount type"
                ));
                return;
            }

            if (amount <= 0) {
                ctx.status(400).json(Map.of(
                        "success", false,
                        "error", "amount must be positive"
                ));
                return;
            }

            System.out.println("📨 Faucet request received:");
            System.out.println("   Unicity ID: " + unicityId);
            System.out.println("   Coin: " + coin);
            System.out.println("   Amount: " + amount);

            // Process request synchronously (wait for completion)
            try {
                var result = faucetService.processFaucetRequest(unicityId, coin, amount).join();

                if (result.success) {
                    System.out.println("✅ Request completed successfully");

                    ctx.json(Map.of(
                            "success", true,
                            "message", result.message,
                            "data", Map.of(
                                    "requestId", result.requestId,
                                    "unicityId", result.unicityId,
                                    "coin", result.coinName,
                                    "symbol", result.coinSymbol,
                                    "amount", result.amount,
                                    "amountInSmallestUnits", result.amountInSmallestUnits,
                                    "recipientNostrPubkey", result.recipientNostrPubkey
                            )
                    ));
                } else {
                    System.err.println("❌ Request failed: " + result.message);

                    // Determine HTTP status code based on error message
                    int statusCode = isUserError(result.message) ? 400 : 500;

                    ctx.status(statusCode).json(Map.of(
                            "success", false,
                            "error", result.message
                    ));
                }
            } catch (Exception ex) {
                System.err.println("❌ Unexpected error: " + ex.getMessage());
                ex.printStackTrace();

                ctx.status(500).json(Map.of(
                        "success", false,
                        "error", "Internal server error: " + ex.getMessage()
                ));
            }

        } catch (Exception e) {
            System.err.println("❌ Failed to parse request: " + e.getMessage());
            e.printStackTrace();

            ctx.status(400).json(Map.of(
                    "success", false,
                    "error", "Invalid request format: " + e.getMessage()
            ));
        }
    }

    /**
     * GET /api/v1/faucet/history
     * Get faucet request history (requires API key)
     * Query params: limit, offset
     * Header: X-API-Key
     */
    private void getHistory(Context ctx) {
        // Verify API key
        String requestApiKey = ctx.header("X-API-Key");
        if (requestApiKey == null || !requestApiKey.equals(apiKey)) {
            ctx.status(401).json(Map.of(
                    "success", false,
                    "error", "Unauthorized: Invalid or missing API key"
            ));
            return;
        }

        try {
            // Parse query parameters
            String limitStr = ctx.queryParam("limit");
            String offsetStr = ctx.queryParam("offset");
            int limit = (limitStr != null) ? Integer.parseInt(limitStr) : 100;
            int offset = (offsetStr != null) ? Integer.parseInt(offsetStr) : 0;

            // Validate limits
            if (limit < 1 || limit > 1000) {
                limit = 100;
            }
            if (offset < 0) {
                offset = 0;
            }

            // Get requests from database
            List<FaucetRequest> requests = faucetService.getDatabase().getAllRequests(limit, offset);
            int totalCount = faucetService.getDatabase().getRequestCount();

            // Convert to response format
            List<Map<String, Object>> requestList = new java.util.ArrayList<>();
            for (var request : requests) {
                Map<String, Object> requestData = new HashMap<>();
                requestData.put("id", request.getId());
                requestData.put("unicityId", request.getUnicityId());
                requestData.put("coinSymbol", request.getCoinSymbol());
                requestData.put("coinName", request.getCoinName());
                requestData.put("amount", request.getAmount());
                requestData.put("amountInSmallestUnits", request.getAmountInSmallestUnits());
                requestData.put("recipientNostrPubkey", request.getRecipientNostrPubkey());
                requestData.put("status", request.getStatus());
                requestData.put("errorMessage", request.getErrorMessage());
                requestData.put("timestamp", request.getTimestamp().toString());
                requestList.add(requestData);
            }

            ctx.json(Map.of(
                    "success", true,
                    "data", Map.of(
                            "requests", requestList,
                            "pagination", Map.of(
                                    "limit", limit,
                                    "offset", offset,
                                    "total", totalCount
                            )
                    )
            ));

        } catch (Exception e) {
            ctx.status(500).json(Map.of(
                    "success", false,
                    "error", "Failed to load history: " + e.getMessage()
            ));
        }
    }

    /**
     * POST /api/v1/admin/refresh-registry
     * Clear registry cache and reload from online source
     * Requires API key
     */
    private void refreshRegistry(Context ctx) {
        // Verify API key
        String requestApiKey = ctx.header("X-API-Key");
        if (requestApiKey == null || !requestApiKey.equals(apiKey)) {
            ctx.status(401).json(Map.of(
                    "success", false,
                    "error", "Unauthorized: Invalid or missing API key"
            ));
            return;
        }

        try {
            System.out.println("🔄 Admin request: Refreshing token registry...");

            // Refresh registry in-place
            UnicityTokenRegistry.getInstance().refresh();

            var coins = faucetService.getSupportedCoins();
            System.out.println("✅ Registry refreshed: " + coins.length + " coins loaded");

            ctx.json(Map.of(
                    "success", true,
                    "message", "Registry refreshed successfully",
                    "coinsLoaded", coins.length
            ));
        } catch (Exception e) {
            System.err.println("❌ Failed to refresh registry: " + e.getMessage());
            e.printStackTrace();

            ctx.status(500).json(Map.of(
                    "success", false,
                    "error", "Failed to refresh registry: " + e.getMessage()
            ));
        }
    }

    /**
     * Determine if an error is a user error (4xx) vs server error (5xx)
     */
    private boolean isUserError(String errorMessage) {
        String lowerMsg = errorMessage.toLowerCase();
        return lowerMsg.contains("nametag not found") ||
               lowerMsg.contains("coin not found") ||
               lowerMsg.contains("invalid amount") ||
               lowerMsg.contains("not found");
    }

    /**
     * Main entry point for the server
     */
    public static void main(String[] args) {
        try {
            // Load configuration
            FaucetConfig config = FaucetConfig.load();

            // Get data directory from environment or use default
            String dataDir = System.getenv("DATA_DIR");
            if (dataDir == null || dataDir.trim().isEmpty()) {
                dataDir = "./data";
            }

            // Get API key from environment
            String apiKey = System.getenv("FAUCET_API_KEY");
            if (apiKey == null || apiKey.trim().isEmpty()) {
                System.err.println("⚠️  WARNING: FAUCET_API_KEY not set, using default key");
                apiKey = "change-me-in-production";
            }

            // Show first 5 chars of API key for verification
            String apiKeyPreview = apiKey.length() >= 5 ? apiKey.substring(0, 5) + "..." : apiKey;
            System.out.println("🔑 API Key: " + apiKeyPreview);

            // Get port from environment or use default
            int port = 8080;
            String portEnv = System.getenv("PORT");
            if (portEnv != null && !portEnv.trim().isEmpty()) {
                try {
                    port = Integer.parseInt(portEnv);
                } catch (NumberFormatException e) {
                    System.err.println("⚠️  Invalid PORT value, using default: 8080");
                }
            }

            // Show first 5 chars of mnemonic for verification
            String mnemonicPreview = config.faucetMnemonic.length() >= 5 ?
                config.faucetMnemonic.substring(0, 5) + "..." : config.faucetMnemonic;
            System.out.println("🔐 Mnemonic: " + mnemonicPreview);

            // Initialize faucet service
            FaucetService faucetService = new FaucetService(config, dataDir);

            // Start server
            FaucetServer server = new FaucetServer(faucetService, apiKey, port, config.aggregatorUrl);
            server.start();

        } catch (Exception e) {
            System.err.println("❌ Failed to start server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
