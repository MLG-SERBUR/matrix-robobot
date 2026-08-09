# AGENTS.md

Matrix bot in Java 17 (Maven, JUnit 5). Room services react to messages, `!commands` handled by `CommandDispatcher`.

- Deploy after any change:  — runs `mvn clean package`, stops/rebuilds/restarts user systemd service `matrix-robobot`. This is the ONLY deploy step; don't hand-run mvn/systemctl.

## Shorthand commands (user says short phrases, no fixed commit convention)

- cpd: commit, push, and deploy. Deploy is done via running `./install_service.sh`
