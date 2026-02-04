#!/bin/bash

# Matrix Robobot Installation Script (systemd)
# This script builds the project and sets it up as a systemd service.

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== Matrix Robobot Installer ===${NC}"

# 1. Check requirements
if ! command -v java &> /dev/null; then
    echo -e "${RED}Error: Java is not installed. Please install Java 17 or newer.${NC}"
    exit 1
fi

if ! command -v mvn &> /dev/null; then
    echo -e "${RED}Error: Maven is not installed.${NC}"
    exit 1
fi

# 2. Check for config.json
if [ ! -f "config.json" ]; then
    echo -e "${RED}Error: config.json not found in the current directory.${NC}"
    echo "Please create config.json from config.example.json before running this installer."
    exit 1
fi

# 3. Build the project
echo -e "${BLUE}Building project with Maven...${NC}"
mvn clean package -DskipTests

JAR_FILE="target/matrix-robobot-1.0.0.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo -e "${RED}Error: JAR file not found at $JAR_FILE after build.${NC}"
    exit 1
fi

# 4. Prepare Service variables
SERVICE_NAME="matrix-bot"
USER_NAME=$(whoami)
WORK_DIR=$(pwd)
JAVA_BIN=$(which java)

echo -e "${BLUE}Creating systemd service...${NC}"

# 5. Create the service file content
SERVICE_CONTENT="[Unit]
Description=Matrix Robobot Service
After=network.target

[Service]
User=$USER_NAME
WorkingDirectory=$WORK_DIR
ExecStart=$JAVA_BIN -jar $JAR_FILE
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target"

# 6. Write to temporary file and move to systemd
echo "$SERVICE_CONTENT" > "$SERVICE_NAME.service"

echo -e "${BLUE}Applying systemd configuration... (sudo required)${NC}"
sudo mv "$SERVICE_NAME.service" "/etc/systemd/system/$SERVICE_NAME.service"
sudo systemctl daemon-reload
sudo systemctl enable "$SERVICE_NAME"
sudo systemctl restart "$SERVICE_NAME"

echo -e "${GREEN}=== Installation Complete! ===${NC}"
echo -e "You can view the logs with: ${BLUE}journalctl -u $SERVICE_NAME -f${NC}"
echo -e "The service will now start automatically on reboot."
