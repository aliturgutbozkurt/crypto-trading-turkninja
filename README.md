# Crypto Trading Bot - Kullanım Kılavuzu

## 🚀 Hızlı Başlangıç

### 1. API Key'lerinizi Ayarlayın
`.env` dosyasını düzenleyin ve Binance API key'lerinizi girin:
```bash
BINANCE_API_KEY=gerçek_api_key_buraya
BINANCE_SECRET_KEY=gerçek_secret_key_buraya
```

### 2. Uygulamayı Başlatın
```bash
./start.sh
```

### 3. Uygulamayı Durdurun
```bash
./stop.sh
```

## ⚙️ Strateji Parametreleri

`src/main/resources/application.properties` dosyasını düzenleyin:

```properties
# RSI eşik değerleri (Daha seçici)
strategy.rsi.buy.threshold=45      # LONG için RSI < 45
strategy.rsi.sell.threshold=55     # SHORT için RSI > 55

# Pozisyon ayarları
strategy.position.min_usdt=4.0
strategy.position.max_percent=0.25

# Risk yönetimi
risk.max_concurrent_positions=7    # Aynı anda 7 pozisyon
```

## 📊 Strateji

- **Trend Following**: BTC yükselişteyken LONG, düşüşteyken SHORT
- **Mean Reversion**: Aşırı satım/alımda pozisyon
- **Risk**: Stop-loss, take-profit, trailing stop
- **Leverage**: 20x Cross Margin

## 🔧 Gereksinimler

- Java 21
- Maven
- MongoDB
- Binance Futures API Key

## 📝 Loglar

```bash
tail -f startup_log.txt
```
