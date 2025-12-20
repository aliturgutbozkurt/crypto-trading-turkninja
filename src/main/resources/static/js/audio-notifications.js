// Audio Notification System for Trading Bot
// Plays sounds when positions are opened/closed

class AudioNotification {
    constructor() {
        this.enabled = true;
        this.audioContext = null;
        this.previousPositions = new Set(); // Track position symbols

        // Initialize on user interaction (required by browsers)
        document.addEventListener('click', () => this.init(), { once: true });
    }

    init() {
        try {
            this.audioContext = new (window.AudioContext || window.webkitAudioContext)();
            console.log('🔊 Audio notification system initialized');
        } catch (e) {
            console.error('Audio context not supported:', e);
            this.enabled = false;
        }
    }

    // Create a pleasant "position opened" sound (ascending notes)
    playPositionOpened() {
        if (!this.enabled || !this.audioContext) return;

        const now = this.audioContext.currentTime;

        // oscillator 1 - ascending
        const osc1 = this.audioContext.createOscillator();
        const gain1 = this.audioContext.createGain();

        osc1.frequency.setValueAtTime(523.25, now); // C5
        osc1.frequency.exponentialRampToValueAtTime(659.25, now + 0.1); // E5
        osc1.frequency.exponentialRampToValueAtTime(783.99, now + 0.2); // G5

        gain1.gain.setValueAtTime(0.3, now);
        gain1.gain.exponentialRampToValueAtTime(0.01, now + 0.3);

        osc1.connect(gain1);
        gain1.connect(this.audioContext.destination);

        osc1.start(now);
        osc1.stop(now + 0.3);

        console.log('🟢 Position opened sound played');
    }

    // Create a "position closed" sound (descending notes)
    playPositionClosed(isProfit = true) {
        if (!this.enabled || !this.audioContext) return;

        const now = this.audioContext.currentTime;

        if (isProfit) {
            // Profit: Pleasant descending chime
            const osc = this.audioContext.createOscillator();
            const gain = this.audioContext.createGain();

            osc.frequency.setValueAtTime(880.00, now); // A5
            osc.frequency.exponentialRampToValueAtTime(659.25, now + 0.15); // E5
            osc.frequency.exponentialRampToValueAtTime(523.25, now + 0.3); // C5

            gain.gain.setValueAtTime(0.3, now);
            gain.gain.exponentialRampToValueAtTime(0.01, now + 0.4);

            osc.connect(gain);
            gain.connect(this.audioContext.destination);

            osc.start(now);
            osc.stop(now + 0.4);

            console.log('💰 Profit exit sound played');
        } else {
            // Loss: Short alert tone
            const osc = this.audioContext.createOscillator();
            const gain = this.audioContext.createGain();

            osc.frequency.setValueAtTime(300, now); // Low tone
            osc.type = 'triangle';

            gain.gain.setValueAtTime(0.2, now);
            gain.gain.exponentialRampToValueAtTime(0.01, now + 0.2);

            osc.connect(gain);
            gain.connect(this.audioContext.destination);

            osc.start(now);
            osc.stop(now + 0.2);

            console.log('📉 Loss exit sound played');
        }
    }

    // Monitor positions and trigger sounds
    checkPositions(newPositions) {
        if (!this.enabled) return;

        // Extract current position symbols
        const currentSymbols = new Set(newPositions.map(p => p.symbol));

        // Detect newly opened positions
        currentSymbols.forEach(symbol => {
            if (!this.previousPositions.has(symbol)) {
                this.playPositionOpened();
            }
        });

        // Detect closed positions - we don't have profit/loss info here
        // So we'll play a neutral close sound
        this.previousPositions.forEach(symbol => {
            if (!currentSymbols.has(symbol)) {
                // Position was closed - default to profit sound
                this.playPositionClosed(true);
            }
        });

        // Update previous positions
        this.previousPositions = currentSymbols;
    }

    // Monitor trade history for precise profit/loss detection
    checkTradeHistory(closedTrades) {
        if (!this.enabled || !closedTrades || closedTrades.length === 0) return;

        // Play sound for the most recent trade if it just closed
        const latestTrade = closedTrades[0];
        if (latestTrade && latestTrade.justClosed) {
            const isProfit = latestTrade.pnl >= 0;
            this.playPositionClosed(isProfit);
        }
    }

    toggle() {
        this.enabled = !this.enabled;
        console.log(`🔊 Audio notifications: ${this.enabled ? 'ON' : 'OFF'}`);
        return this.enabled;
    }
}

// Global instance
const audioNotifier = new AudioNotification();
