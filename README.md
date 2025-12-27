# Matrix Hello Bot (Java)

Simple minimal Matrix bot that sends a "Hello, world!" message to a room using the Matrix Client-Server HTTP API.

Prerequisites
- Java 17+
- Maven

Environment variables
- `MATRIX_HOMESERVER_URL` — homeserver URL (e.g. `https://matrix.org` or your server)
- `MATRIX_ACCESS_TOKEN` — access token for the bot/user
- `MATRIX_ROOM_ID` — room id (e.g. `!abcdef:matrix.org`) or room alias (e.g. `#room:matrix.org`)

Build
```bash
mvn -q -DskipTests package
```

Run
```bash
export MATRIX_HOMESERVER_URL="https://matrix.org"
export MATRIX_ACCESS_TOKEN="YOUR_ACCESS_TOKEN"
export MATRIX_ROOM_ID="!yourRoomId:matrix.org"
java -jar target/matrix-hello-bot-1.0.0.jar "Optional custom message here"
```

Notes
- This is intentionally minimal and uses the homeserver HTTP API directly.
- For a long-running bot that listens for events use `/sync` or a higher-level SDK.
