# Matrix-Robobot (Java)

Matrix bot that helps you stay in the loop by tracking read receipts, providing conversation summaries, and enabling semantic search through chat history.

## Architecture

The bot uses a **separate room configuration**:
- **Command Room**: Where the bot listens for commands (such as `!testcommand` and `!export`) and receives all responses
- **Export Room**: Where chat history is fetched from for exports and AI queries (never receives messages)

This allows you to keep command execution and responses in one room while fetching history from another.

## Configuration

Create a `config.json` file in the same directory as the JAR:

```json
{
  "homeserver": "https://matrix.org",
  "accessToken": "YOUR_ACCESS_TOKEN",
  "commandRoomId": "!commandRoom:matrix.example.com",
  "exportRoomId": "!exportRoom:matrix.example.com",
  "cerebrasApiKey": "YOUR_CEREBRAS_API_KEY",
  "groqApiKey": "YOUR_GROQ_API_KEY",
  "openrouterApiKey": "YOUR_OPENROUTER_API_KEY",
  "cerebrasModels": ["qwen-3-235b-a22b-instruct-2507"],
  "groqModels": ["meta-llama/llama-4-scout-17b-16e-instruct"],
  "openrouterModels": ["openrouter/auto"],
  "paseoProvider": "opencode/hy4-flash-free"
}
```

**Fields:**
- `homeserver`: Your Matrix homeserver URL
- `accessToken`: Access token for the bot/user
- `commandRoomId`: Room ID where commands are listened for and responses are sent
- `exportRoomId`: Room ID where chat history is fetched from (for exports and AI queries)
- `cerebrasApiKey`: API key for Cerebras (optional)
- `groqApiKey`: API key for Groq (optional)
- `openrouterApiKey`: API key for OpenRouter (optional)
- `geminiApiKey`: API key for Google Gemini (optional)
- `mistralApiKey`: API key for Mistral (optional)
- `zaiApiKey`: API key for Z.AI (optional)
- `cloudflareApiKey`: API token for Cloudflare Workers AI (optional; requires `cloudflareAccountId`)
- `cloudflareAccountId`: Cloudflare account ID (required for Workers AI)
- `ollamaApiKey`: API key for Ollama Cloud (ollama.com) (optional)
- `sambaNovaApiKey`: API key for SambaNova (optional)
- `cerebrasModels`: List of Cerebras models to try in sequence on fallback
- `groqModels`: List of Groq models to try in sequence on fallback
- `openrouterModels`: List of OpenRouter models (first model used)
- `ollamaModels`: Ollama Cloud models, tried last in automatic fallback. Beam is tried first when configured; remaining Ollama models are randomized per query since usage is tracked for the whole account rather than per model.
- `geminiModels`/`mistralModels`/`zaiModels`/`cloudflareModels`/`sambaNovaModels`: Model lists for the other providers. They are tried after Groq and before OpenRouter. Cloudflare model order is randomized per query since usage is tracked for the whole account rather than per model.
- `paseoProvider`: Paseo provider/model used for bounded AI queries and automatic TLDRs when their selected history contains images (defaults to `opencode/hy4-flash-free`).

Paseo image queries require Node.js/npm and a running Paseo daemon. `./install_service.sh` installs the bridge dependency; the daemon defaults to `ws://127.0.0.1:6767/ws`. To override the connection, create `~/.config/matrix-robobot/paseo.env` with `PASEO_WS_URL=...` and, when needed, `PASEO_DAEMON_PASSWORD=...`. Requests without images and unbounded history continue to use the configured AI provider chain.

## Commands

All commands must be sent in the **command room** specified in your config:

- `!testcommand` — Bot replies `Hello, world!` in the **command room**
- `!export <N>` — Export the last N messages from the **export room** and post the file info in the **command room**
  - Example: `!export 100` will write a file like `!exportRoom-...-last100msgs-<ts>.txt`
- `!export <N>h` — Export the last N hours of chat from the **export room** and post the file info in the **command room**
  - Example: `!export 12h` will write a file like `!exportRoom-...-last12h-<ts>.txt`
- `!export <link> [+|-<N>]` — Export from a message link with optional +/- count
  - Example: `!export https://matrix.to/#/.../$eventId +50` will export 50 messages after the linked message

## Prerequisites
- Java 17+
- Maven
- Node.js 20.19+ and npm (for Paseo image queries)

## Build
```bash
mvn -q -DskipTests package
```

## Run
```bash
java -jar target/matrix-hello-bot-1.0.0.jar config.json
```

Or with Maven:
```bash
mvn exec:java -Dexec.mainClass="com.robomwm.ai.matrixrobobot.MatrixRobobot" -Dexec.classpathScope=runtime -Dexec.args="config.json"
```

## Notes
- The bot only processes commands in the configured command room
- All responses are sent to the command room
- Chat history for exports and AI queries is fetched from the export room
- The bot never sends messages to the export room
- This separation allows for better organization and security (e.g., command room could be private, export room could be public)
