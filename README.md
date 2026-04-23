# Matrix-Robobot (Java)

Matrix bot that helps you stay in the loop by tracking read receipts, providing conversation summaries, and enabling semantic search through chat history.

## Architecture

The bot uses a **separate room configuration**:
- **Command Room**: Where the bot listens for commands (`!testcommand`, `!export`, `!arliai`) and receives all responses
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
  "arliApiKey": "YOUR_ARLI_API_KEY"
}
```

**Fields:**
- `homeserver`: Your Matrix homeserver URL
- `accessToken`: Access token for the bot/user
- `commandRoomId`: Room ID where commands are listened for and responses are sent
- `exportRoomId`: Room ID where chat history is fetched from (for exports and AI queries)
- `arliApiKey`: API key for Arli AI (optional, but required for `!arliai` commands)

## Commands

All commands must be sent in the **command room** specified in your config:

- `!testcommand` — Bot replies `Hello, world!` in the **command room**
- `!export<N>h` — Export the last N hours of chat from the **export room** and post the file info in the **command room**
  - Example: `!export12h` will write a file like `!exportRoom-...-last12h-<ts>.txt`
- `!arliai <TZ> <N>h [question]` — Queries Arli AI with chat logs from the **export room** for the last N hours. Chat logs will use the specified timezone for timestamps.
  - Example: `!arliai PST 12h` — Analyzes last 12 hours of chat with PST timestamps
  - Example: `!arliai EST 6h What was the main topic of discussion?` — Analyzes last 6 hours with a specific question
- `!arliai-ts <YYYY-MM-DD-HH-MM> <TZ> <N>h [question]` — Queries Arli AI with chat logs from the **export room** starting at a specific date and time, covering the next N hours. Chat logs will use the specified timezone for timestamps.
  - Example: `!arliai-ts 2024-12-30-23-59 PST 24h` — Analyzes 24 hours of chat starting at 23:59 PST on December 30, 2024
  - Example: `!arliai-ts 2024-12-30-14-30 EST 6h What was discussed?` — Analyzes 6 hours starting at 14:30 EST on December 30, 2024 with a specific question
  - Supported timezones: PST, PDT, MST, MDT, CST, CDT, EST, EDT, UTC, GMT

## Prerequisites
- Java 17+
- Maven

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
