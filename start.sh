#!/bin/bash

# Crypto Trading Bot - Start Script
# This script starts InfluxDB, Grafana, and the trading application

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
echo ""

# Start InfluxDB and Grafana with Docker Compose
echo "🐳 Starting InfluxDB and Grafana containers..."
docker-compose up -d

# Wait for InfluxDB to be ready
echo "⏳ Waiting for InfluxDB to be ready..."
sleep 3

# Check if containers are running
if docker ps | grep -q "crypto-trading-influxdb"; then
    echo "✅ InfluxDB is running on http://localhost:8086"
else
    echo "⚠️  Warning: InfluxDB container may not be running properly"
fi

if docker ps | grep -q "crypto-trading-grafana"; then
    echo "✅ Grafana is running on http://localhost:3000"
else
    echo "⚠️  Warning: Grafana container may not be running properly"
fi

echo ""
echo "🚀 Starting Crypto Trading Bot..."
echo ""

# Add local Maven to PATH if it exists
if [ -d "/tmp/apache-maven-3.9.6/bin" ]; then
    export PATH=/tmp/apache-maven-3.9.6/bin:$PATH
    echo "✅ Using local Maven from /tmp/apache-maven-3.9.6"
fi

# Start ML Signal Classifier Service (if enabled)
ML_ENABLED=$(grep "ml.signal.validator.enabled=true" src/main/resources/application.properties 2>/dev/null)
if [ ! -z "$ML_ENABLED" ]; then
    echo "🤖 Starting ML Signal Classifier Service..."
    if [ -f "ml_service/signal_model.pkl" ]; then
        cd ml_service
        nohup python3 -m uvicorn signal_classifier:app --host 0.0.0.0 --port 8000 > ../ml_service.log 2>&1 &
        ML_PID=$!
        echo $ML_PID > ../ml_service.pid
        cd ..
        sleep 2
        if curl -s http://localhost:8000/health > /dev/null 2>&1; then
            echo "✅ ML Service running on http://localhost:8000 (PID: $ML_PID)"
        else
            echo "⚠️  ML Service started but health check failed"
        fi
    else
        echo "⚠️  ML model not trained yet. Run: cd ml_service && python train_model.py"
    fi
else
    echo "ℹ️  ML Signal Validator is disabled (set ml.signal.validator.enabled=true to enable)"
fi

echo ""

# Start the application and log to file
mvn clean spring-boot:run | tee startup_log.txt
