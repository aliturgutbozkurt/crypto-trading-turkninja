#!/bin/bash

# Crypto Trading Bot - Stop Script
# This script stops all running instances of the application

echo "🛑 Stopping Crypto Trading Bot..."

# Find and kill all Maven JavaFX processes
pkill -f "javafx:run"

# Also kill any Java processes running the app
pkill -f "com.turkninja.App"

echo "✅ All bot processes stopped"
