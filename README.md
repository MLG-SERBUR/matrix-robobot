# Matrix Hello Bot (Java)

Simple minimal Matrix bot that sends a "Hello, world!" message to a room using the Matrix Client-Server HTTP API.

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
- `!arliai` or `!arliai <N>h` or `!arliai <N>h <question>` — Queries Arli AI with chat logs from the **export room** and posts the summary/answer in the **command room**
  - Example: `!arliai`, `!arliai 12h`, or `!arliai 6h What was the main topic of discussion?`

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
mvn exec:java -Dexec.mainClass="com.example.matrixbot.MatrixHelloBot" -Dexec.classpathScope=runtime -Dexec.args="config.json"
```

## Notes
- The bot only processes commands in the configured command room
- All responses are sent to the command room
- Chat history for exports and AI queries is fetched from the export room
- The bot never sends messages to the export room
- This separation allows for better organization and security (e.g., command room could be private, export room could be public)
