// TradingView Chart Integration
// Real-time candlestick chart with trade markers

let chart = null;
let candlestickSeries = null;
let markers = [];
let currentChartSymbol = 'BTCUSDT';

// Initialize TradingView Chart
function initChart() {
    const chartDiv = document.getElementById('chartDiv');

    if (!chartDiv) {
        console.error('Chart div not found');
        return;
    }

    // Create chart
    chart = LightweightCharts.createChart(chartDiv, {
        width: chartDiv.clientWidth,
        height: 500,
        layout: {
            background: { color: '#131722' },
            textColor: '#d1d4dc',
        },
        grid: {
            vertLines: { color: '#1e222d' },
            horzLines: { color: '#1e222d' },
        },
        crosshair: {
            mode: LightweightCharts.CrosshairMode.Normal,
        },
        rightPriceScale: {
            borderColor: '#2B2B43',
        },
        timeScale: {
            borderColor: '#2B2B43',
            timeVisible: true,
            secondsVisible: false,
        },
    });

    // Add candlestick series
    candlestickSeries = chart.addCandlestickSeries({
        upColor: '#00ff88',
        downColor: '#ff4976',
        borderDownColor: '#ff4976',
        borderUpColor: '#00ff88',
        wickDownColor: '#ff4976',
        wickUpColor: '#00ff88',
    });

    // Resize chart on window resize
    window.addEventListener('resize', () => {
        if (chart && chartDiv) {
            chart.applyOptions({
                width: chartDiv.clientWidth
            });
        }
    });

    console.log('✅ TradingView chart initialized');

    // Load initial data
    loadHistoricalData(currentChartSymbol);
}

// Load historical kline data from Binance
async function loadHistoricalData(symbol) {
    try {
        console.log(`📥 Loading historical data for ${symbol}...`);

        // Binance API endpoint (no API key needed for klines)
        const interval = '5m';  // 5 minute candles
        const limit = 500;  // Last 500 candles

        const url = `https://fapi.binance.com/fapi/v1/klines?symbol=${symbol}&interval=${interval}&limit=${limit}`;

        const response = await fetch(url);
        const klines = await response.json();

        if (!klines || klines.length === 0) {
            console.error('No kline data received');
            return;
        }

        // Convert to TradingView format
        const candles = klines.map(kline => ({
            time: Math.floor(kline[0] / 1000), // Convert to seconds
            open: parseFloat(kline[1]),
            high: parseFloat(kline[2]),
            low: parseFloat(kline[3]),
            close: parseFloat(kline[4]),
        }));

        candlestickSeries.setData(candles);
        console.log(`✅ Loaded ${candles.length} candles for ${symbol}`);

        // Auto-scale to fit data
        chart.timeScale().fitContent();

    } catch (error) {
        console.error('Error loading historical data:', error);
    }
}

// Change symbol
function changeSymbol(symbol) {
    currentChartSymbol = symbol;
    document.getElementById('currentSymbol').textContent = symbol;

    // Update button states
    document.querySelectorAll('.symbol-btn').forEach(btn => {
        btn.classList.remove('active');
    });
    event.target.classList.add('active');

    // Clear existing data and load new
    loadHistoricalData(symbol);

    // Clear markers
    candlestickSeries.setMarkers([]);
    markers = [];

    console.log(`📊 Switched to ${symbol}`);
}

// Add trade marker to chart
function addTradeMarker(time, price, type, text) {
    const marker = {
        time: Math.floor(time / 1000), // Convert to seconds
        position: type === 'LONG' ? 'belowBar' : 'aboveBar',
        color: type === 'LONG' ? '#00ff00' : '#ff4444',
        shape: type === 'LONG' ? 'arrowUp' : 'arrowDown',
        text: text || type,
    };

    markers.push(marker);
    candlestickSeries.setMarkers(markers);

    console.log(`📍 Added ${type} marker at ${price}`);
}

// WebSocket connection for real-time updates
let ws = null;

function connectWebSocket() {
    const symbol = currentChartSymbol.toLowerCase();
    const wsUrl = `wss://fstream.binance.com/ws/${symbol}@kline_5m`;

    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
        console.log(`✅ WebSocket connected to ${symbol}`);
    };

    ws.onmessage = (event) => {
        const data = JSON.parse(event.data);
        const kline = data.k;

        // Update last candle or add new candle
        const candle = {
            time: Math.floor(kline.t / 1000),
            open: parseFloat(kline.o),
            high: parseFloat(kline.h),
            low: parseFloat(kline.l),
            close: parseFloat(kline.c),
        };

        candlestickSeries.update(candle);
    };

    ws.onerror = (error) => {
        console.error('WebSocket error:', error);
    };

    ws.onclose = () => {
        console.log('WebSocket closed, reconnecting in 5s...');
        setTimeout(connectWebSocket, 5000);
    };
}

// Mock trade data for demonstration
function simulateTradeMarkers() {
    // This will be replaced with actual trade data from your backend
    setTimeout(() => {
        const now = Date.now();

        // Example: Add a LONG marker 2 hours ago
        addTradeMarker(now - 2 * 60 * 60 * 1000, 42000, 'LONG', 'LONG @42000');

        // Example: Add a SHORT marker 1 hour ago
        addTradeMarker(now - 1 * 60 * 60 * 1000, 42500, 'SHORT', 'EXIT @42500');
    }, 2000);
}

// Initialize chart when DOM is ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
        initChart();
        connectWebSocket();
        simulateTradeMarkers();
    });
} else {
    initChart();
    connectWebSocket();
    simulateTradeMarkers();
}

// API to add trade markers (call this from your Java backend)
window.addTrade = function (symbol, timestamp, price, side, note) {
    if (symbol === currentChartSymbol) {
        addTradeMarker(timestamp, price, side, note);
    }
};

// Export for external use
window.chartAPI = {
    changeSymbol,
    addTradeMarker,
    getCurrentSymbol: () => currentChartSymbol
};
