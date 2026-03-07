# Unicity Token Faucet

A production-ready REST API server and CLI application for distributing Unicity testnet tokens via web interface or command line.

## Features

- 🌐 **REST API Server** - Full REST API under `/api/v1/`
- 💧 **Web Interface** - User-friendly faucet UI at `/faucet/index.html`
- 📊 **History Tracking** - Track all faucet requests with API key protected history
- 🗄️ **Database Storage** - SQLite database for request history
- 📁 **Token Storage** - Automatic token file storage in `./data/tokens/`
- 🐳 **Docker Ready** - Single command deployment with Docker Compose
- 🔐 **API Key Protection** - Secure history and admin access with API key authentication
- 🔄 **Admin API** - Refresh token registry without restart
- 📱 **Phone Number Support** - Send tokens to phone numbers (e.g., +14155552671)
- 🔒 **Privacy-Preserving** - SHA-256 hashed nametags for Nostr lookups

## Quick Start - Web Server

### Deploy with Docker Compose (Recommended)

1. **Navigate to faucet directory:**
   ```bash
   cd faucet
   ```

2. **Set up environment variables (required):**
   ```bash
   cp .env.example .env
   # Edit .env and set:
   # - FAUCET_API_KEY (for history access)
   # - FAUCET_MNEMONIC (your faucet wallet mnemonic - GENERATE A NEW ONE!)
   ```

3. **Start the server:**
   ```bash
   docker compose up
   ```

4. **Access the faucet:**
   - Web UI: http://localhost:8081/faucet/index.html
   - History: http://localhost:8081/faucet/history/index.html
   - API: http://localhost:8081/api/v1/faucet/

### Stop the server:
```bash
docker compose down
```

### View logs:
```bash
docker compose logs -f
```

## Quick Start - CLI

### Run the CLI Faucet

```bash
# Default coin (Solana) with default amount
./gradlew run --args="--nametag=alice"

# Send to a phone number (automatically normalized and hashed)
./gradlew run --args="--nametag=+14155552671"

# Specify amount and coin
./gradlew run --args="--nametag=alice --amount=0.01 --coin=bitcoin"
./gradlew run --args="--nametag=alice --amount=0.5 --coin=ethereum"
./gradlew run --args="--nametag=alice --amount=100 --coin=tether"
```

## REST API Documentation

### Endpoints

All endpoints are prefixed with `/api/v1/faucet/`.

#### `GET /api/v1/faucet/coins`
Get list of supported crypto assets.

**Response:**
```json
{
  "success": true,
  "coins": [
    {
      "id": "...",
      "name": "solana",
      "symbol": "SOL",
      "decimals": 9,
      "description": "Solana",
      "iconUrl": "https://..."
    }
  ]
}
```

#### `POST /api/v1/faucet/request`
Submit a faucet request to mint and send tokens.

**Request Body:**
```json
{
  "unicityId": "alice",
  "coin": "solana",
  "amount": 0.05
}
```

**Response:**
```json
{
  "success": true,
  "message": "Token sent successfully",
  "data": {
    "requestId": 1,
    "unicityId": "alice",
    "coin": "Solana",
    "symbol": "SOL",
    "amount": 0.05,
    "amountInSmallestUnits": "50000000",
    "recipientNostrPubkey": "..."
  }
}
```

**Error Response:**
```json
{
  "success": false,
  "error": "Nametag not found: alice"
}
```

#### `GET /api/v1/faucet/history`
Get faucet request history (requires API key).

**Headers:**
```
X-API-Key: your-api-key
```

**Query Parameters:**
- `limit` (optional, default: 100, max: 1000)
- `offset` (optional, default: 0)

**Response:**
```json
{
  "success": true,
  "data": {
    "requests": [...],
    "pagination": {
      "limit": 100,
      "offset": 0,
      "total": 250
    }
  }
}
```

#### `GET /health`
Health check endpoint.

**Response:**
```json
{
  "status": "healthy"
}
```

### Admin Endpoints

#### `POST /api/v1/admin/refresh-registry`
Refresh token registry from remote URL without restart.

**Headers:**
```
X-API-Key: your-api-key
```

**Response:**
```json
{
  "success": true,
  "message": "Registry refreshed successfully",
  "coinsLoaded": 5
}
```

**Example:**
```bash
curl -X POST https://your-faucet-url/api/v1/admin/refresh-registry \
  -H "X-API-Key: YOUR_FAUCET_API_KEY"
```

## Configuration

### Environment Variables

| Variable | Description | Default | Required |
|----------|-------------|---------|----------|
| `FAUCET_MNEMONIC` | BIP-39 mnemonic phrase for faucet wallet | Falls back to config file | **Yes** |
| `FAUCET_API_KEY` | API key for history access | `change-me-in-production` | **Yes** |
| `DATA_DIR` | Directory for database and token files | `./data` | No |
| `PORT` | Server port | `8080` | No |

### Faucet Configuration

Edit `src/main/resources/faucet-config.json`:

```json
{
  "registryUrl": "https://raw.githubusercontent.com/unicitynetwork/unicity-ids/refs/heads/main/unicity-ids.testnet.json",
  "nostrRelay": "ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080",
  "aggregatorUrl": "https://goggregator-test.unicity.network",
  "faucetMnemonic": "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
  "defaultAmount": 1000,
  "defaultCoin": "solana"
}
```

**IMPORTANT:**
- Set `FAUCET_MNEMONIC` environment variable in `.env` (overrides the config file)
- The mnemonic in config file is just a fallback
- **Generate a new secure mnemonic for production!**

## Supported Coins

All coins are loaded dynamically from the registry:

| Coin | Symbol | Decimals | CoinGecko ID |
|------|--------|----------|--------------|
| solana | SOL | 9 | solana |
| bitcoin | BTC | 8 | bitcoin |
| ethereum | ETH | 18 | ethereum |
| tether | USDT | 6 | tether |
| usd-coin | USDC | 6 | usd-coin |

To add new coins, update the [unicity-ids.testnet.json](https://github.com/unicitynetwork/unicity-ids/blob/main/unicity-ids.testnet.json) registry.

## Data Persistence

All data is stored in the `./data` directory:

- `./data/faucet.db` - SQLite database with request history
- `./data/tokens/` - Individual token files for each request

This directory is automatically created and persisted via Docker volume mount.

## How It Works

### 1. Registry Loading
- Fetches coin definitions from GitHub
- Caches locally in `~/.unicity/registry-cache.json`
- Cache valid for 24 hours
- Use `--refresh` to force update

### 2. Nametag Resolution (Privacy-Preserving)
- Queries Nostr relay: `{"kinds": [30078], "#t": ["nametag"]}`
- Uses SHA-256 hashed nametags (never exposes raw nametags)
- Gets recipient's Nostr public key from binding
- Creates proxy address: `ProxyAddress.create(TokenId.fromNameTag(nametag))`

### 3. Token Minting
- Uses token type from registry (non-fungible "unicity")
- Uses coin ID from registry
- Applies correct decimals (e.g., 1.5 SOL → 1,500,000,000 units)
- Mints to faucet's address first

### 4. Token Transfer to Proxy Address
- Creates `TransferCommitment` to proxy address
- Submits to aggregator
- Waits for inclusion proof
- Creates transfer transaction

### 5. Nostr Message Delivery
- Encrypts transfer package with NIP-04
- Sends to recipient's Nostr pubkey
- Format: `token_transfer:{"sourceToken":"...","transferTx":"..."}`
- Wallet receives, verifies, and finalizes

## Architecture

```
┌─────────────────┐
│  Web Browser    │
│  (User)         │
└────────┬────────┘
         │ HTTP
         ▼
┌─────────────────┐
│  Javalin        │  REST API Server
│  Web Server     │  - /api/v1/faucet/*
│                 │  - /api/v1/admin/*
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ FaucetService   │  Business Logic
│                 │  - Token minting
│                 │  - Nametag resolution
│                 │  - Nostr delivery
└────────┬────────┘
         │
         ├──────────────────┐
         ▼                  ▼
┌─────────────────┐  ┌─────────────────┐
│ SQLite Database │  │ Token Storage   │
│ (History)       │  │ (./data/tokens) │
└─────────────────┘  └─────────────────┘
```

## Local Development (without Docker)

### Run the server:

1. **Build the project:**
   ```bash
   ./gradlew build
   ```

2. **Set environment variables:**
   ```bash
   export FAUCET_API_KEY="your-api-key"
   export DATA_DIR="./data"
   export PORT=8080
   ```

3. **Run the server:**
   ```bash
   ./gradlew run --args="org.unicitylabs.faucet.FaucetServer"
   ```

### Run tests:

```bash
./gradlew test
./gradlew test --tests "FaucetE2ETest.testCompleteTokenTransferFlow"
```

## CLI Options

```
Usage: faucet [-hV] -n=<nametag> [-a=<amount>] [-c=<coin>] [--refresh] [--config=<configPath>]

Options:
  -n, --nametag=<nametag>   Recipient's nametag or phone number (required)
  -a, --amount=<amount>     Token amount in human-readable units (e.g., 0.05)
  -c, --coin=<coin>         Coin to mint (e.g., 'bitcoin', 'ethereum', 'solana')
      --refresh             Force refresh registry from GitHub (ignores cache)
      --config=<path>       Path to config file
  -h, --help                Show this help message and exit
  -V, --version             Print version information and exit
```

## Security Considerations

1. **API Key**: Always set a strong `FAUCET_API_KEY` in production
2. **Mnemonic**: The faucet mnemonic in config should be kept secure
3. **Network**: Consider running behind a reverse proxy (nginx, Traefik) with HTTPS
4. **Rate Limiting**: Consider implementing rate limiting for production use
5. **Privacy**: Nametags are automatically SHA-256 hashed for privacy

## Troubleshooting

### Docker build fails
- Ensure Docker has enough memory (4GB+ recommended)
- Check that all source files are present
- Try `docker compose build --no-cache`

### Server won't start
- Check that port 8080 is not already in use
- Verify faucet-config.json is valid JSON
- Check logs: `docker compose logs -f`

### Database errors
- Ensure `./data` directory has write permissions
- Delete `./data/faucet.db` to reset the database

### Token delivery fails
- Verify Nostr relay is accessible
- Check that nametag has been minted and published
- Ensure aggregator URL is correct

### Registry cache issues
```bash
# Clear cache
rm ~/.unicity/registry-cache.json
# Or use --refresh flag
./gradlew run --args="--nametag=alice --refresh"
```

## Project Structure

```
faucet/
├── src/
│   ├── main/
│   │   ├── java/org/unicitylabs/faucet/
│   │   │   ├── db/                    # Database models and DAO
│   │   │   ├── FaucetServer.java      # REST API server
│   │   │   ├── FaucetService.java     # Business logic
│   │   │   ├── FaucetCLI.java         # CLI interface
│   │   │   ├── TokenMinter.java       # Token minting
│   │   │   ├── NametagResolver.java   # Nametag resolution
│   │   │   └── UnicityTokenRegistry.java  # Registry loader
│   │   └── resources/
│   │       ├── public/faucet/         # Web UI files
│   │       ├── faucet-config.json     # Faucet configuration
│   │       └── trustbase-testnet.json # Trust base
│   └── test/
├── build.gradle.kts
├── Dockerfile
├── docker-compose.yml
└── README.md
```

## Dependencies

- Unicity Java SDK 1.2+ (state transitions, proxy addressing)
- Unicity Nostr SDK 1.0+ (Nostr client, token transfer protocol)
- Javalin 5.6+ (web server)
- SQLite JDBC (database)
- Jackson (JSON/CBOR serialization)
- BouncyCastle (cryptography)
- Picocli (CLI)
- BitcoinJ (BIP-39 mnemonic)

## License

Part of the Unicity Protocol project - MIT License
