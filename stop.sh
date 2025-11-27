#!/bin/bash

# Crypto Trading Bot - Stop Script
# This script stops the application and Docker containers

echo "🛑 Stopping Crypto Trading Bot..."

# Kill any Java processes running the app
pkill -f "spring-boot:run"
pkill -f "com.turkninja.App"

echo "✅ Application processes stopped"

# Stop Docker containers
echo "🐳 Stopping InfluxDB and Grafana containers..."
docker-compose down

echo "✅ All services stopped successfully"

