#!/bin/bash

# Crypto Trading Bot - Stop Script
# This script stops the application and Docker containers

echo "🛑 Stopping Crypto Trading Bot..."

# Kill any Java processes running the app
pkill -f "spring-boot:run"
pkill -f "com.turkninja.App"

echo "✅ Application processes stopped"

# Stop ML Signal Classifier Service
echo "🤖 Stopping ML Signal Classifier Service..."
if [ -f "ml_service.pid" ]; then
    ML_PID=$(cat ml_service.pid)
    if ps -p $ML_PID > /dev/null 2>&1; then
        kill $ML_PID 2>/dev/null
        echo "✅ ML Service stopped (PID: $ML_PID)"
    else
        echo "ℹ️  ML Service was not running"
    fi
    rm -f ml_service.pid
else
    # Try to find and kill uvicorn process
    pkill -f "uvicorn signal_classifier:app" 2>/dev/null
    echo "✅ ML Service processes cleaned up"
fi

# Stop Docker containers
echo "🐳 Stopping InfluxDB and Grafana containers..."
docker-compose down

echo "✅ All services stopped successfully"
