package org.unicitylabs.faucet.db;

import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.sqlite.SQLiteConfig;

/**
 * Database access layer for faucet requests
 */
public class FaucetDatabase {

    private final String dbPath;
    private final SQLiteConfig connectionConfig;

    public FaucetDatabase(String dataDir) {
        // Create data directory if it doesn't exist
        File dir = new File(dataDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        this.dbPath = "jdbc:sqlite:" + dataDir + "/faucet.db";
        this.connectionConfig = new SQLiteConfig();
        connectionConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        connectionConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        connectionConfig.setBusyTimeout(10000);
        initializeDatabase();
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(dbPath, connectionConfig.toProperties());
    }

    private void initializeDatabase() {
        String createTableSQL =
            "CREATE TABLE IF NOT EXISTS faucet_requests (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "unicity_id TEXT NOT NULL," +
            "coin_symbol TEXT NOT NULL," +
            "coin_name TEXT NOT NULL," +
            "coin_id TEXT NOT NULL," +
            "amount REAL NOT NULL," +
            "amount_in_smallest_units TEXT NOT NULL," +
            "recipient_nostr_pubkey TEXT," +
            "token_file_path TEXT," +
            "status TEXT NOT NULL," +
            "error_message TEXT," +
            "timestamp INTEGER NOT NULL" +
            ");" +
            "CREATE INDEX IF NOT EXISTS idx_timestamp ON faucet_requests(timestamp DESC);" +
            "CREATE INDEX IF NOT EXISTS idx_unicity_id ON faucet_requests(unicity_id);";

        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createTableSQL);
            System.out.println("✅ Database initialized: " + dbPath);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    /**
     * Insert a new faucet request
     */
    public long insertRequest(FaucetRequest request) throws SQLException {
        String sql =
            "INSERT INTO faucet_requests " +
            "(unicity_id, coin_symbol, coin_name, coin_id, amount, amount_in_smallest_units, " +
            "recipient_nostr_pubkey, token_file_path, status, error_message, timestamp) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = openConnection()) {
            // Insert the record
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, request.getUnicityId());
                pstmt.setString(2, request.getCoinSymbol());
                pstmt.setString(3, request.getCoinName());
                pstmt.setString(4, request.getCoinId());
                pstmt.setDouble(5, request.getAmount());
                pstmt.setString(6, request.getAmountInSmallestUnits());
                pstmt.setString(7, request.getRecipientNostrPubkey());
                pstmt.setString(8, request.getTokenFilePath());
                pstmt.setString(9, request.getStatus());
                pstmt.setString(10, request.getErrorMessage());
                pstmt.setLong(11, request.getTimestamp().getEpochSecond());

                pstmt.executeUpdate();
            }

            // Get the last inserted ID using SQLite's last_insert_rowid()
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    request.setId(id);
                    return id;
                } else {
                    throw new SQLException("Creating request failed, no ID obtained.");
                }
            }
        }
    }

    /**
     * Update a faucet request
     */
    public void updateRequest(FaucetRequest request) throws SQLException {
        String sql =
            "UPDATE faucet_requests " +
            "SET recipient_nostr_pubkey = ?, " +
            "token_file_path = ?, " +
            "status = ?, " +
            "error_message = ? " +
            "WHERE id = ?";

        try (Connection conn = openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, request.getRecipientNostrPubkey());
            pstmt.setString(2, request.getTokenFilePath());
            pstmt.setString(3, request.getStatus());
            pstmt.setString(4, request.getErrorMessage());
            pstmt.setLong(5, request.getId());

            pstmt.executeUpdate();
        }
    }

    /**
     * Get all requests, ordered by timestamp descending
     */
    public List<FaucetRequest> getAllRequests(int limit, int offset) throws SQLException {
        String sql =
            "SELECT * FROM faucet_requests " +
            "ORDER BY timestamp DESC " +
            "LIMIT ? OFFSET ?";

        List<FaucetRequest> requests = new ArrayList<>();

        try (Connection conn = openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, limit);
            pstmt.setInt(2, offset);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    requests.add(mapResultSetToRequest(rs));
                }
            }
        }

        return requests;
    }

    /**
     * Get total count of requests
     */
    public int getRequestCount() throws SQLException {
        String sql = "SELECT COUNT(*) FROM faucet_requests";

        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        }
    }

    private FaucetRequest mapResultSetToRequest(ResultSet rs) throws SQLException {
        FaucetRequest request = new FaucetRequest();
        request.setId(rs.getLong("id"));
        request.setUnicityId(rs.getString("unicity_id"));
        request.setCoinSymbol(rs.getString("coin_symbol"));
        request.setCoinName(rs.getString("coin_name"));
        request.setCoinId(rs.getString("coin_id"));
        request.setAmount(rs.getDouble("amount"));
        request.setAmountInSmallestUnits(rs.getString("amount_in_smallest_units"));
        request.setRecipientNostrPubkey(rs.getString("recipient_nostr_pubkey"));
        request.setTokenFilePath(rs.getString("token_file_path"));
        request.setStatus(rs.getString("status"));
        request.setErrorMessage(rs.getString("error_message"));
        request.setTimestamp(Instant.ofEpochSecond(rs.getLong("timestamp")));
        return request;
    }

    public static class TimeSeriesPoint {
        public final long bucket;
        public final String coinSymbol;
        public final String status;
        public final int count;
        public final double totalAmount;

        public TimeSeriesPoint(long bucket, String coinSymbol, String status, int count, double totalAmount) {
            this.bucket = bucket;
            this.coinSymbol = coinSymbol;
            this.status = status;
            this.count = count;
            this.totalAmount = totalAmount;
        }
    }

    public static class Counted {
        public final String key;
        public final int count;
        public final double totalAmount;

        public Counted(String key, int count, double totalAmount) {
            this.key = key;
            this.count = count;
            this.totalAmount = totalAmount;
        }
    }

    public List<TimeSeriesPoint> getTimeSeries(long bucketSeconds, long sinceEpochSeconds) throws SQLException {
        String sql =
            "SELECT (timestamp / ?) * ? AS bucket, coin_symbol, status, " +
            "COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS total " +
            "FROM faucet_requests " +
            "WHERE timestamp >= ? " +
            "GROUP BY bucket, coin_symbol, status " +
            "ORDER BY bucket ASC";

        List<TimeSeriesPoint> points = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, bucketSeconds);
            pstmt.setLong(2, bucketSeconds);
            pstmt.setLong(3, sinceEpochSeconds);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    points.add(new TimeSeriesPoint(
                        rs.getLong("bucket"),
                        rs.getString("coin_symbol"),
                        rs.getString("status"),
                        rs.getInt("cnt"),
                        rs.getDouble("total")
                    ));
                }
            }
        }
        return points;
    }

    public List<Counted> getTopNametags(int limit, long sinceEpochSeconds) throws SQLException {
        String sql =
            "SELECT unicity_id, COUNT(*) AS cnt " +
            "FROM faucet_requests " +
            "WHERE timestamp >= ? " +
            "GROUP BY unicity_id " +
            "ORDER BY cnt DESC " +
            "LIMIT ?";

        List<Counted> result = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sinceEpochSeconds);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new Counted(rs.getString("unicity_id"), rs.getInt("cnt"), 0));
                }
            }
        }
        return result;
    }

    public List<Counted> getTopCoins(long sinceEpochSeconds) throws SQLException {
        String sql =
            "SELECT coin_symbol, COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS total " +
            "FROM faucet_requests " +
            "WHERE timestamp >= ? AND status = 'SUCCESS' " +
            "GROUP BY coin_symbol " +
            "ORDER BY cnt DESC";

        List<Counted> result = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sinceEpochSeconds);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new Counted(
                        rs.getString("coin_symbol"),
                        rs.getInt("cnt"),
                        rs.getDouble("total")
                    ));
                }
            }
        }
        return result;
    }

    public List<Counted> getTopErrors(int limit, long sinceEpochSeconds) throws SQLException {
        String sql =
            "SELECT error_message, COUNT(*) AS cnt " +
            "FROM faucet_requests " +
            "WHERE timestamp >= ? AND status = 'FAILED' AND error_message IS NOT NULL " +
            "GROUP BY error_message " +
            "ORDER BY cnt DESC " +
            "LIMIT ?";

        List<Counted> result = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sinceEpochSeconds);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new Counted(rs.getString("error_message"), rs.getInt("cnt"), 0));
                }
            }
        }
        return result;
    }

    public Map<String, Object> getSummary(long sinceEpochSeconds) throws SQLException {
        String sql =
            "SELECT " +
            "  COUNT(*) AS total, " +
            "  SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count, " +
            "  SUM(CASE WHEN status = 'FAILED'  THEN 1 ELSE 0 END) AS failed_count, " +
            "  COUNT(DISTINCT unicity_id) AS unique_nametags " +
            "FROM faucet_requests " +
            "WHERE timestamp >= ?";

        Map<String, Object> summary = new LinkedHashMap<>();
        try (Connection conn = openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sinceEpochSeconds);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    int total = rs.getInt("total");
                    int success = rs.getInt("success_count");
                    int failed = rs.getInt("failed_count");
                    int unique = rs.getInt("unique_nametags");
                    summary.put("total", total);
                    summary.put("success", success);
                    summary.put("failed", failed);
                    summary.put("uniqueNametags", unique);
                    summary.put("successRate", total > 0 ? (double) success / total : 0.0);
                }
            }
        }
        return summary;
    }

    public List<Counted> getHighVolumeNametags(int minRequests, long sinceEpochSeconds) throws SQLException {
        String sql =
            "SELECT unicity_id, COUNT(*) AS cnt " +
            "FROM faucet_requests " +
            "WHERE timestamp >= ? " +
            "GROUP BY unicity_id " +
            "HAVING cnt >= ? " +
            "ORDER BY cnt DESC " +
            "LIMIT 50";

        List<Counted> result = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sinceEpochSeconds);
            pstmt.setInt(2, minRequests);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new Counted(rs.getString("unicity_id"), rs.getInt("cnt"), 0));
                }
            }
        }
        return result;
    }
}
