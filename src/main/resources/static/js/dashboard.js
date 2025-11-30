// WebSocket connection
let stompClient = null;
let socket = null;

// Load positions on page load
document.addEventListener('DOMContentLoaded', function () {
    loadAccount();
    loadPositions();
    connectWebSocket();
    loadTradeHistory(); // Initial load

    // Auto-reload data every 1 second (Polling fallback)
    setInterval(loadData, 1000);

    // Auto-reload positions from Binance every 10 seconds
    setInterval(reloadPositions, 10000);

    // Auto-reload trade history every 10 seconds
    setInterval(loadTradeHistory, 10000);
});

// Wrapper to load both account and positions
function loadData() {
    loadAccount();
    loadPositions();
    loadSignals(); // Poll signals as well
}

// Load signals via REST (Fallback for WebSocket)
async function loadSignals() {
    try {
        const response = await fetch('/api/signals');
        const signals = await response.json();

        // Clear existing list to avoid duplicates (simple approach)
        const signalsList = document.getElementById('signalsList');
        signalsList.innerHTML = '';

        // Add signals in reverse order (newest first)
        signals.forEach(signal => addSignal(signal));
    } catch (error) {
        console.error('Error loading signals:', error);
    }
}

function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = protocol + '//' + window.location.host + '/ws-stream';

    console.log('🔌 Connecting to WebSocket:', wsUrl);
    socket = new WebSocket(wsUrl);

    socket.onopen = function () {
        console.log('✅ WebSocket Connected');
        document.getElementById('lastUpdate').textContent = 'Live (Connected)';
        document.getElementById('lastUpdate').style.color = '#27ae60';
    };

    socket.onmessage = function (event) {
        // console.log('📩 WS Message:', event.data.length + ' bytes'); // Debug log
        try {
            const data = JSON.parse(event.data);
            if (data.type === 'UPDATE') {
                updateDashboard(data);
            } else if (data.type === 'SIGNAL') {
                addSignal(data.signal);
            } else if (data.type === 'METRICS_UPDATE') {
                // Update metrics in real-time when trades close
                if (data.metrics) {
                    renderMetrics(data.metrics);
                }
            }
        } catch (e) {
            console.error('❌ Error parsing WebSocket message:', e);
        }
    };

    socket.onclose = function (event) {
        console.log('⚠️ WebSocket Disconnected (Code: ' + event.code + '). Reconnecting in 3s...');
        document.getElementById('lastUpdate').textContent = 'Disconnected (Reconnecting...)';
        document.getElementById('lastUpdate').style.color = '#e74c3c';

        // Clear socket reference
        socket = null;

        // Reconnect after delay
        setTimeout(connectWebSocket, 3000);
    };

    socket.onerror = function (error) {
        console.error('❌ WebSocket Error:', error);
        // On error, close is usually called automatically, triggering reconnection
    };
}

function updateDashboard(data) {
    // Update Account Stats
    if (data.account) {
        const acc = data.account;
        document.getElementById('totalBalance').textContent = '$' + acc.totalBalance.toFixed(2);
        document.getElementById('unrealizedPnL').textContent = '$' + acc.unrealizedPnL.toFixed(2);
        document.getElementById('unrealizedPnL').className = 'stat-value ' + (acc.unrealizedPnL >= 0 ? 'profit' : 'loss');
        document.getElementById('activePositions').textContent = acc.activePositions;
        document.getElementById('strategyStatus').textContent = acc.strategyStatus;
        document.getElementById('strategyStatus').className = 'stat-value ' + (acc.strategyStatus === 'Active' ? 'active' : 'inactive');
    }

    // Update Positions Table (Only if positions data is provided)
    if (data.positions) {
        const tbody = document.getElementById('positionsBody');
        const positions = data.positions;

        if (!positions || positions.length === 0) {
            tbody.innerHTML = '<tr><td colspan="10" class="no-data">No active positions. Strategy is scanning...</td></tr>';
        } else {
            // Simple full redraw for now (can be optimized later if needed)
            let html = '';
            const now = Date.now();
            positions.forEach(pos => {
                // Calculate duration
                const entryTime = pos.entryTime || now;
                const durationMs = now - entryTime;
                const durationSec = Math.floor(durationMs / 1000);
                const hours = Math.floor(durationSec / 3600);
                const minutes = Math.floor((durationSec % 3600) / 60);
                const seconds = durationSec % 60;
                const durationStr = `${hours}h ${minutes}m ${seconds}s`;

                html += `
                <tr>
                    <td class="symbol">${pos.symbol}</td>
                    <td class="side ${pos.side.toLowerCase()}">${pos.side}</td>
                    <td>$${pos.entryPrice.toFixed(4)}</td>
                    <td>$${pos.currentPrice.toFixed(4)}</td>
                    <td class="${pos.unrealizedPnL >= 0 ? 'profit' : 'loss'}">$${pos.unrealizedPnL.toFixed(2)}</td>
                    <td class="${pos.roi >= 0 ? 'profit' : 'loss'}">${pos.roi.toFixed(2)}%</td>
                    <td>$${pos.positionSizeUsdt.toFixed(2)}</td>
                    <td>${pos.balancePercent.toFixed(2)}%</td>
                    <td>${durationStr}</td>
                    <td>
                        <button onclick="closePosition('${pos.symbol}')" class="btn-sm btn-danger">Close</button>
                    </td>
                </tr>
            `;
            });
            tbody.innerHTML = html;
        }
    }

    // Flash update indicator
    const updateEl = document.getElementById('lastUpdate');
    updateEl.textContent = 'Live (' + new Date().toLocaleTimeString() + ')';
}

// Close individual position
async function closePosition(symbol) {
    if (!confirm('Are you sure you want to close ' + symbol + ' position?')) {
        return;
    }

    const statusEl = document.getElementById('statusMessage');
    statusEl.textContent = 'Closing ' + symbol + '...';
    statusEl.className = 'status-message warning';

    try {
        const response = await fetch('/api/close-position?symbol=' + symbol, { method: 'POST' });
        const result = await response.text();

        statusEl.textContent = '✅ ' + result;
        statusEl.className = 'status-message success';

        // Clear message after 3 seconds
        setTimeout(() => {
            statusEl.textContent = '';
        }, 3000);

    } catch (error) {
        statusEl.textContent = '❌ Error: ' + error.message;
        statusEl.className = 'status-message error';
    }
}

// Load account information (Initial load)
async function loadAccount() {
    try {
        const response = await fetch('/api/account');
        const data = await response.json();
        updateDashboard({ account: data });
    } catch (error) {
        console.error('Error loading account:', error);
    }
}

// Load positions (Initial load)
async function loadPositions() {
    try {
        const response = await fetch('/api/positions');
        const positions = await response.json();
        updateDashboard({ positions: positions });
    } catch (error) {
        console.error('Error loading positions:', error);
    }
}

// Reload positions manually
async function reloadPositions() {
    const statusEl = document.getElementById('statusMessage');
    statusEl.textContent = 'Reloading positions from Binance...';
    statusEl.className = 'status-message info';

    try {
        const response = await fetch('/api/reload-positions', { method: 'POST' });
        const result = await response.text();

        statusEl.textContent = '✅ ' + result;
        statusEl.className = 'status-message success';

        setTimeout(() => {
            statusEl.textContent = '';
        }, 3000);

    } catch (error) {
        statusEl.textContent = '❌ Error: ' + error.message;
        statusEl.className = 'status-message error';
    }
}

// Emergency exit
async function emergencyExit() {
    if (!confirm('⚠️ EMERGENCY EXIT - This will close ALL positions immediately. Are you sure?')) {
        return;
    }

    const statusEl = document.getElementById('statusMessage');
    statusEl.textContent = '🚨 Executing emergency exit...';
    statusEl.className = 'status-message warning';

    try {
        const response = await fetch('/api/emergency-exit', { method: 'POST' });
        const result = await response.text();

        statusEl.textContent = '✅ ' + result;
        statusEl.className = 'status-message success';

    } catch (error) {
        statusEl.textContent = '❌ Error: ' + error.message;
        statusEl.className = 'status-message error';
    }
}

// Add trading signal to the signals list
function addSignal(signal) {
    const signalsList = document.getElementById('signalsList');

    // Create signal element
    const signalEl = document.createElement('div');
    signalEl.className = `signal-item ${signal.type.toLowerCase()} ${signal.executed ? 'executed' : 'blocked'}`;

    const time = new Date(signal.timestamp).toLocaleTimeString();
    const icon = signal.type === 'BUY' ? '🟢' : '🔴';
    const statusIcon = signal.executed ? '✅' : '🚫';

    signalEl.innerHTML = `
        <div class="signal-header">
            <span class="signal-icon">${icon}</span>
            <span class="signal-symbol">${signal.symbol}</span>
            <span class="signal-type">${signal.type}</span>
            <span class="signal-status">${statusIcon} ${signal.status}</span>
            <span class="signal-time">${time}</span>
        </div>
        <div class="signal-details">
            ${signal.type !== 'INFO' ? `<div class="signal-price">$${signal.price.toFixed(2)}</div>` : ''}
            <div class="signal-reason">${signal.reason}</div>
        </div>
    `;

    // Add to top of list with animation
    signalsList.insertBefore(signalEl, signalsList.firstChild);

    // Animate entrance
    setTimeout(() => signalEl.classList.add('show'), 10);

    // Keep only last 50 signals
    while (signalsList.children.length > 50) {
        signalsList.removeChild(signalsList.lastChild);
    }
}

// --- Trading History Logic ---

let historyPage = 0;
let historyLimit = 10;
let historyTab = 'all'; // 'all', 'active', 'closed'

// Load history on startup
document.addEventListener('DOMContentLoaded', function () {
    loadTradeHistory();
});

async function loadTradeHistory() {
    const tbody = document.getElementById('historyBody');
    const pageInfo = document.getElementById('pageInfo');
    const prevBtn = document.getElementById('prevBtn');
    const nextBtn = document.getElementById('nextBtn');

    // Show loading state if first load
    if (tbody.innerHTML.includes('Loading')) {
        tbody.innerHTML = '<tr><td colspan="10" class="no-data"><div class="loading"></div> Loading history...</td></tr>';
    }

    try {
        const offset = historyPage * historyLimit;
        const response = await fetch(`/api/trade-history?limit=${historyLimit}&offset=${offset}`);
        const data = await response.json();

        renderTradeHistory(data);

        // Render metrics
        if (data.stats) {
            renderMetrics(data.stats);
        }

        // Update pagination controls
        pageInfo.textContent = `Page ${historyPage + 1}`;
        prevBtn.disabled = historyPage === 0;

        // Disable next button if we received fewer items than limit (end of list)
        // Note: This is an approximation. Ideally backend returns total count.
        const activeCount = data.active ? data.active.length : 0;
        const closedCount = data.closed ? data.closed.length : 0;
        const totalItems = activeCount + closedCount;

        // If we are showing "active" tab, we might have more pages of closed trades even if active is empty
        // For simplicity, we'll rely on the 'closed' list size for pagination when not in 'active' mode

        if (historyTab === 'active') {
            nextBtn.disabled = true; // Active positions usually fit in one page
        } else {
            nextBtn.disabled = closedCount < historyLimit;
        }

    } catch (error) {
        console.error('Error loading trade history:', error);
        tbody.innerHTML = `<tr><td colspan="10" class="no-data" style="color: #e74c3c">Error loading history: ${error.message}</td></tr>`;
    }
}

function renderTradeHistory(data) {
    const tbody = document.getElementById('historyBody');
    let trades = [];

    // Combine/Filter based on tab
    if (historyTab === 'all' || historyTab === 'active') {
        if (data.active) trades = trades.concat(data.active);
    }

    if (historyTab === 'all' || historyTab === 'closed') {
        if (data.closed) trades = trades.concat(data.closed);
    }

    if (trades.length === 0) {
        tbody.innerHTML = '<tr><td colspan="10" class="no-data">No trades found.</td></tr>';
        return;
    }

    let html = '';
    trades.forEach(trade => {
        const isClosed = trade.status === 'CLOSED';
        const pnlClass = trade.pnl >= 0 ? 'profit' : 'loss';
        const pnlPrefix = trade.pnl >= 0 ? '+' : '';
        const roiClass = trade.pnlPercent >= 0 ? 'profit' : 'loss';
        const roiPrefix = trade.pnlPercent >= 0 ? '+' : '';

        // Format duration
        let durationStr = '-';
        if (trade.durationSeconds) {
            const mins = Math.floor(trade.durationSeconds / 60);
            const secs = trade.durationSeconds % 60;
            if (mins > 60) {
                const hours = Math.floor(mins / 60);
                durationStr = `${hours}h ${mins % 60}m`;
            } else {
                durationStr = `${mins}m ${secs}s`;
            }
        }

        // Format time
        const timeStr = new Date(trade.timestamp).toLocaleString();

        html += `
            <tr>
                <td><span class="status-badge ${isClosed ? 'closed' : 'active'}">${trade.status}</span></td>
                <td class="symbol">${trade.symbol}</td>
                <td class="side ${trade.side.toLowerCase()}">${trade.side}</td>
                <td>$${trade.entryPrice.toFixed(4)}</td>
                <td>${trade.exitPrice ? '$' + trade.exitPrice.toFixed(4) : '-'}</td>
                <td class="${pnlClass}">${pnlPrefix}$${trade.pnl.toFixed(2)}</td>
                <td class="${roiClass}">${roiPrefix}${trade.pnlPercent.toFixed(2)}%</td>
                <td>${durationStr}</td>
                <td>${trade.exitReason || '-'}</td>
                <td style="font-size: 0.85em; color: #7f8c8d;">${timeStr}</td>
            </tr>
        `;
    });

    tbody.innerHTML = html;
}

function switchHistoryTab(tab) {
    historyTab = tab;
    historyPage = 0; // Reset to first page

    // Update buttons
    document.querySelectorAll('.history-tabs .tab-btn').forEach(btn => {
        btn.classList.remove('active');
        if (btn.textContent.toLowerCase() === tab) {
            btn.classList.add('active');
        }
    });

    loadTradeHistory();
}

function nextHistoryPage() {
    historyPage++;
    loadTradeHistory();
}

function prevHistoryPage() {
    if (historyPage > 0) {
        historyPage--;
        loadTradeHistory();
    }
}

// Render metrics (Total PnL, Win Rate, Total Trades, and Advanced Stats)
function renderMetrics(stats) {
    const totalPnL = stats.totalPnL || 0;
    const winRate = stats.winRate || 0;
    const totalTrades = stats.totalTrades || 0;
    const maxDrawdown = stats.maxDrawdown || 0;
    const sharpeRatio = stats.sharpeRatio || 0;
    const avgTrade = stats.avgTrade || 0;
    const bestTrade = stats.bestTrade || 0;

    // Format and color Total PnL
    const pnlColor = totalPnL >= 0 ? '#27ae60' : '#e74c3c';
    const pnlSign = totalPnL >= 0 ? '+' : '';
    document.getElementById('totalPnL').textContent = `${pnlSign}$${totalPnL.toFixed(2)}`;
    document.getElementById('totalPnL').style.color = pnlColor;

    // Format Win Rate
    document.getElementById('winRate').textContent = `${winRate.toFixed(1)}%`;

    // Total Trades
    document.getElementById('totalTrades').textContent = totalTrades;

    // Max Drawdown (displayed as percentage or absolute value)
    document.getElementById('maxDrawdown').textContent = `$${maxDrawdown.toFixed(2)}`;
    document.getElementById('maxDrawdown').style.color = maxDrawdown > 50 ? '#e74c3c' : '#f39c12';

    // Sharpe Ratio
    const sharpeColor = sharpeRatio > 1 ? '#27ae60' : sharpeRatio > 0 ? '#f39c12' : '#e74c3c';
    document.getElementById('sharpeRatio').textContent = sharpeRatio.toFixed(2);
    document.getElementById('sharpeRatio').style.color = sharpeColor;

    // Average Trade
    const avgColor = avgTrade >= 0 ? '#27ae60' : '#e74c3c';
    const avgSign = avgTrade >= 0 ? '+' : '';
    document.getElementById('avgTrade').textContent = `${avgSign}$${avgTrade.toFixed(2)}`;
    document.getElementById('avgTrade').style.color = avgColor;

    // Best Trade
    const bestColor = bestTrade >= 0 ? '#27ae60' : '#3498db';
    const bestSign = bestTrade >= 0 ? '+' : '';
    document.getElementById('bestTrade').textContent = `${bestSign}$${bestTrade.toFixed(2)}`;
    document.getElementById('bestTrade').style.color = bestColor;
}
