"""
ML Signal Classifier Service
FastAPI-based microservice for validating trading signals using XGBoost.
"""

import os
import logging
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import joblib
import numpy as np

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Signal Classifier", version="1.0.0")

# Model path
MODEL_PATH = os.getenv("MODEL_PATH", "signal_model.pkl")
model = None


class SignalRequest(BaseModel):
    """Trading signal features for prediction"""
    symbol: str
    side: str  # "BUY" or "SELL"
    rsi: float
    macd: float
    macd_signal: float
    ema_alignment: float  # Distance from EMA as %
    atr_percent: float  # ATR as % of price
    volume_ratio: float  # Current volume / avg volume
    cvd: float  # Cumulative Volume Delta
    adx: float  # ADX trend strength
    price: float


class SignalResponse(BaseModel):
    """Prediction response"""
    symbol: str
    side: str
    probability: float
    recommended: bool
    confidence: str


@app.on_event("startup")
async def load_model():
    """Load the trained model on startup"""
    global model
    try:
        if os.path.exists(MODEL_PATH):
            model = joblib.load(MODEL_PATH)
            logger.info(f"✅ Model loaded from {MODEL_PATH}")
        else:
            logger.warning(f"⚠️ Model not found at {MODEL_PATH} - using fallback mode")
            model = None
    except Exception as e:
        logger.error(f"❌ Failed to load model: {e}")
        model = None


@app.get("/health")
async def health_check():
    """Health check endpoint"""
    return {
        "status": "healthy",
        "model_loaded": model is not None
    }


@app.post("/predict", response_model=SignalResponse)
async def predict_signal(request: SignalRequest):
    """
    Predict signal success probability
    
    Features:
    - RSI: Momentum indicator (0-100)
    - MACD/Signal: Trend confirmation
    - EMA Alignment: Multi-timeframe trend
    - ATR %: Volatility measure
    - Volume Ratio: Volume confirmation
    - CVD: Order flow direction
    - ADX: Trend strength
    """
    try:
        # Prepare features
        features = np.array([[
            request.rsi,
            request.macd,
            request.macd_signal,
            request.macd - request.macd_signal,  # MACD histogram
            request.ema_alignment,
            request.atr_percent,
            request.volume_ratio,
            request.cvd,
            request.adx,
            1.0 if request.side == "BUY" else 0.0  # Side encoding
        ]])

        if model is not None:
            # Use trained model
            probability = float(model.predict_proba(features)[0][1])
        else:
            # Fallback: rule-based scoring
            probability = calculate_fallback_score(request)

        # Determine recommendation
        min_probability = float(os.getenv("MIN_PROBABILITY", "0.6"))
        recommended = probability >= min_probability

        # Confidence level
        if probability >= 0.8:
            confidence = "HIGH"
        elif probability >= 0.6:
            confidence = "MEDIUM"
        else:
            confidence = "LOW"

        logger.info(f"📊 Signal {request.symbol} {request.side}: {probability:.2%} ({confidence})")

        return SignalResponse(
            symbol=request.symbol,
            side=request.side,
            probability=probability,
            recommended=recommended,
            confidence=confidence
        )

    except Exception as e:
        logger.error(f"Prediction error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


def calculate_fallback_score(request: SignalRequest) -> float:
    """
    Rule-based scoring when model is not available
    Returns probability between 0 and 1
    """
    score = 0.5  # Base score

    # RSI scoring (momentum confirmation)
    if request.side == "BUY":
        if 40 <= request.rsi <= 60:
            score += 0.1
        elif request.rsi < 30:  # Oversold - good for longs
            score += 0.05
    else:  # SELL
        if 40 <= request.rsi <= 60:
            score += 0.1
        elif request.rsi > 70:  # Overbought - good for shorts
            score += 0.05

    # ADX scoring (trend strength)
    if request.adx > 25:
        score += 0.15
    elif request.adx > 20:
        score += 0.08

    # CVD confirmation
    if (request.side == "BUY" and request.cvd > 0) or \
       (request.side == "SELL" and request.cvd < 0):
        score += 0.1

    # Volume confirmation
    if request.volume_ratio > 1.2:
        score += 0.1

    # EMA alignment (trend direction)
    if (request.side == "BUY" and request.ema_alignment > 0) or \
       (request.side == "SELL" and request.ema_alignment < 0):
        score += 0.1

    # Clamp to [0, 1]
    return max(0.0, min(1.0, score))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
