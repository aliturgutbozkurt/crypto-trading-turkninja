#!/bin/bash

# Crypto Trading Bot - Restart Script
# This script stops the running bot and restarts it with the latest code

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

echo -e "${BLUE}🔄 Crypto Trading Bot - Restart Script${NC}"
echo -e "${BLUE}======================================${NC}"
echo ""

# Step 1: Stop the running application
echo -e "${YELLOW}🛑 Step 1: Stopping running application...${NC}"

# Find the PID of the running Spring Boot application
PID=$(ps aux | grep -i 'spring-boot:run' | grep -v grep | awk '{print $2}')

if [ -z "$PID" ]; then
    echo -e "${YELLOW}⚠️  No running application found${NC}"
else
    echo -e "${GREEN}📍 Found running application (PID: $PID)${NC}"
    echo -e "${YELLOW}🔪 Killing process $PID...${NC}"
    kill -9 $PID
    sleep 2
    
    # Verify it's killed
    if ps -p $PID > /dev/null 2>&1; then
        echo -e "${RED}❌ Failed to kill process $PID${NC}"
        echo -e "${YELLOW}Trying with sudo...${NC}"
        sudo kill -9 $PID
    else
        echo -e "${GREEN}✅ Application stopped successfully${NC}"
    fi
fi

# Also kill any orphaned Java processes related to the bot
echo -e "${YELLOW}🧹 Cleaning up any orphaned Java processes...${NC}"
JAVA_PIDS=$(ps aux | grep -i 'com.turkninja' | grep -v grep | awk '{print $2}')
if [ ! -z "$JAVA_PIDS" ]; then
    echo "$JAVA_PIDS" | xargs kill -9 2>/dev/null
    echo -e "${GREEN}✅ Cleaned up orphaned processes${NC}"
fi

# Stop ML Signal Classifier Service
echo -e "${YELLOW}🤖 Stopping ML Signal Classifier Service...${NC}"
if [ -f "ml_service.pid" ]; then
    ML_PID=$(cat ml_service.pid)
    if ps -p $ML_PID > /dev/null 2>&1; then
        kill $ML_PID 2>/dev/null
        echo -e "${GREEN}✅ ML Service stopped (PID: $ML_PID)${NC}"
    fi
    rm -f ml_service.pid
else
    pkill -f "uvicorn signal_classifier:app" 2>/dev/null
    echo -e "${GREEN}✅ ML Service cleaned up${NC}"
fi

echo ""

# Step 2: Rebuild the application (optional - uncomment if you want to rebuild)
echo -e "${YELLOW}🔨 Step 2: Rebuilding application...${NC}"

# Add local Maven to PATH if it exists
if [ -d "/tmp/apache-maven-3.9.6/bin" ]; then
    export PATH=/tmp/apache-maven-3.9.6/bin:$PATH
    echo -e "${GREEN}✅ Using local Maven from /tmp/apache-maven-3.9.6${NC}"
fi

# Quick rebuild (skip tests for faster restart)
mvn clean compile -DskipTests -q
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Application rebuilt successfully${NC}"
else
    echo -e "${RED}❌ Build failed! Check for compilation errors.${NC}"
    exit 1
fi

echo ""

# Step 3: Restart the application
echo -e "${YELLOW}🚀 Step 3: Starting application...${NC}"
echo ""

# Load environment variables from .env if exists
if [ -f ".env" ]; then
    echo -e "${GREEN}📋 Loading environment variables from .env...${NC}"
    export $(cat .env | grep -v '^#' | xargs)
fi

# Make sure Docker services are running
echo -e "${BLUE}🐳 Checking Docker services...${NC}"
if ! docker ps | grep -q "crypto-trading-influxdb"; then
    echo -e "${YELLOW}⚠️  InfluxDB not running, starting Docker services...${NC}"
    docker-compose up -d
    sleep 3
else
    echo -e "${GREEN}✅ Docker services already running${NC}"
fi

echo ""
echo -e "${GREEN}════════════════════════════════════════${NC}"
echo -e "${GREEN}🚀 Starting Crypto Trading Bot...${NC}"
echo -e "${GREEN}════════════════════════════════════════${NC}"
echo ""

# Start the application
./start.sh
