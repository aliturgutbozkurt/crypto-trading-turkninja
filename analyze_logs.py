
import re

def analyze_signals():
    current_config = ""
    signal_count = 0
    max_signals = -1
    best_config = ""
    
    with open("config_signals.txt", "r") as f:
        for line in f:
            if "Strategy Config Loaded" in line:
                # New config started, check previous one
                if current_config and signal_count > max_signals:
                    max_signals = signal_count
                    best_config = current_config
                
                # Reset for new config
                current_config = line.strip()
                signal_count = 0
            elif "SIGNAL" in line:
                signal_count += 1
        
        # Check last block
        if current_config and signal_count > max_signals:
            max_signals = signal_count
            best_config = current_config
            
    print(f"Best Config found with {max_signals} signals:")
    print(best_config)

if __name__ == "__main__":
    analyze_signals()
