#!/bin/bash

# Crypto Trading Bot - Start Script
# This script loads environment variables and starts the application

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

# Check if .env file exists
if [ ! -f ".env" ]; then
    echo "❌ Error: .env file not found!"
    echo "Please create a .env file with your API keys."
    exit 1
fi

# Load environment variables from .env
echo "📋 Loading environment variables from .env..."
export $(cat .env | grep -v '^#' | xargs)

# Check if required variables are set
if [ -z "$BINANCE_API_KEY" ] || [ -z "$BINANCE_SECRET_KEY" ]; then
    echo "❌ Error: BINANCE_API_KEY or BINANCE_SECRET_KEY not set in .env"
    echo "Please edit .env file and add your Binance API credentials."
    exit 1
fi

echo "✅ Environment variables loaded successfully"
echo "🚀 Starting Crypto Trading Bot..."
echo ""

# Start the application
# Add local Maven to PATH if it exists
if [ -d "/tmp/apache-maven-3.9.6/bin" ]; then
    export PATH=/tmp/apache-maven-3.9.6/bin:$PATH
    echo "✅ Using local Maven from /tmp/apache-maven-3.9.6"
fi

# Start the application and log to file
mvn clean spring-boot:run | tee startup_log.txt
