#!/bin/bash

# Matrix Robobot Uninstallation Script
# This script removes the systemd service.

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

SERVICE_NAME="matrix-robobot"

echo -e "${BLUE}=== Matrix Robobot Uninstaller ===${NC}"

# 1. Stop and disable the service
if systemctl list-unit-files | grep -q "$SERVICE_NAME.service"; then
    echo -e "${BLUE}Stopping and disabling service: $SERVICE_NAME...${NC}"
    sudo systemctl stop "$SERVICE_NAME" || true
    sudo systemctl disable "$SERVICE_NAME"
    
    # 2. Remove the service file
    echo -e "${BLUE}Removing service file...${NC}"
    sudo rm "/etc/systemd/system/$SERVICE_NAME.service"
    
    # 3. Reload systemd
    sudo systemctl daemon-reload
    sudo systemctl reset-failed
    
    echo -e "${GREEN}=== Uninstallation Complete! ===${NC}"
    echo "The service has been removed from your system."
else
    echo -e "${RED}Service $SERVICE_NAME not found.${NC}"
fi
