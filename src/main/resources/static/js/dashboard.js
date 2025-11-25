// WebSocket connection
let stompClient = null;
let socket = null;

// Load positions on page load
document.addEventListener('DOMContentLoaded', function () {
    loadAccount();
    loadPositions();
    connectWebSocket();

    // Auto-reload positions every 10 seconds as requested
    setInterval(reloadPositions, 10000);
});

function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = protocol + '//' + window.location.host + '/ws-stream';

    console.log('Connecting to WebSocket:', wsUrl);
    socket = new WebSocket(wsUrl);

    socket.onopen = function () {
        console.log('WebSocket Connected');
        document.getElementById('lastUpdate').textContent = 'Live (Connected)';
        document.getElementById('lastUpdate').style.color = '#27ae60';
    };

    socket.onmessage = function (event) {
        try {
            const data = JSON.parse(event.data);
            if (data.type === 'UPDATE') {
                updateDashboard(data);
            } else if (data.type === 'SIGNAL') {
                addSignal(data.signal);
            }
        } catch (e) {
            console.error('Error parsing WebSocket message:', e);
        }
    };

    socket.onclose = function () {
        console.log('WebSocket Disconnected. Reconnecting in 3s...');
        document.getElementById('lastUpdate').textContent = 'Disconnected (Reconnecting...)';
        document.getElementById('lastUpdate').style.color = '#e74c3c';
        setTimeout(connectWebSocket, 3000);
    };

    socket.onerror = function (error) {
        console.error('WebSocket Error:', error);
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

    // Update Positions Table
    const tbody = document.getElementById('positionsBody');
    const positions = data.positions || [];

    if (positions.length === 0) {
        tbody.innerHTML = '<tr><td colspan="8" class="no-data">No active positions. Strategy is scanning...</td></tr>';
    } else {
        // Simple full redraw for now (can be optimized later if needed)
        let html = '';
        positions.forEach(pos => {
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
                    <td>
                        <button onclick="closePosition('${pos.symbol}')" class="btn-sm btn-danger">Close</button>
                    </td>
                </tr>
            `;
        });
        tbody.innerHTML = html;
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
        // Initial render handled by WebSocket update usually, but good for first paint
    } catch (error) {
        console.error('Error loading account:', error);
    }
}

// Load positions (Initial load)
async function loadPositions() {
    try {
        const response = await fetch('/api/positions');
        const positions = await response.json();
        // Initial render handled by WebSocket update
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
            <div class="signal-price">$${signal.price.toFixed(2)}</div>
            <div class="signal-reason">${signal.reason}</div>
        </div>
    `;

    // Add to top of list with animation
    signalsList.insertBefore(signalEl, signalsList.firstChild);

    // Animate entrance
    setTimeout(() => signalEl.classList.add('show'), 10);

    // Keep only last 20 signals
    while (signalsList.children.length > 20) {
        signalsList.removeChild(signalsList.lastChild);
    }
}
