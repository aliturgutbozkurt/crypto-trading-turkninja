# Telegram Bot Kurulum Rehberi

## 1. Bot Oluştur (2 dakika)
- Telegram'da `@BotFather` ara
- `/newbot` komutu gönder
- Bot ismi ve username belirle
- Token'ı kopyala

## 2. Chat ID Al (1 dakika)
- Botuna `/start` gönder
- Tarayıcıda aç: `https://api.telegram.org/bot<TOKEN>/getUpdates`
- JSON'da `"chat":{"id":...}` değerini bul

## 3. Config Güncelle
```properties
telegram.enabled=true
telegram.bot.token=BURAYA_TOKEN
telegram.chat.id=BURAYA_CHAT_ID
```

## 4. Restart
Kurulum sonrası restart edince bottan bildirimler gelmeye başlayacak! 🚀

## Bildirim Örnekleri

**Position Açıldığında:**
```
📈 Position Opened
Symbol: SOLUSDT
Side: SHORT
Entry: $135.72
Quantity: 30.00
Size: $200.00
```

**Trailing Stop Tetiklendiğinde:**
```
🎯 Trailing Stop Triggered
Symbol: SOLUSDT SHORT
Extreme: $135.65
Current: $135.92
Profit Locked: 0.35%
```

**High Slippage Uyarısı:**
```
⚠️ High Slippage Detected
Symbol: LINKUSDT
Slippage: 1.20%
Action: Exiting early
```

Yardım lazım olursa söyle!
