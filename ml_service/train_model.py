"""
ML Model Training Script
Trains XGBoost classifier on historical trade data from InfluxDB.
"""

import os
import logging
import pandas as pd
import numpy as np
from influxdb_client import InfluxDBClient
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, accuracy_score
from xgboost import XGBClassifier
import joblib
from dotenv import load_dotenv

# Load environment variables
load_dotenv("../.env")

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# InfluxDB configuration
INFLUXDB_URL = os.getenv("INFLUXDB_URL", "http://localhost:8086")
INFLUXDB_TOKEN = os.getenv("INFLUXDB_TOKEN", "")
INFLUXDB_ORG = os.getenv("INFLUXDB_ORG", "turkninja")
INFLUXDB_BUCKET = os.getenv("INFLUXDB_BUCKET", "trading_data")


def fetch_trade_data() -> pd.DataFrame:
    """
    Fetch historical trade data from InfluxDB
    Returns DataFrame with trade outcomes and features
    """
    logger.info("📊 Fetching trade data from InfluxDB...")

    if not INFLUXDB_TOKEN:
        logger.warning("⚠️ InfluxDB token not configured, using synthetic data")
        return generate_synthetic_data()

    try:
        client = InfluxDBClient(
            url=INFLUXDB_URL,
            token=INFLUXDB_TOKEN,
            org=INFLUXDB_ORG
        )
        query_api = client.query_api()

        # Query closed positions with PnL
        flux_query = f'''
        from(bucket: "{INFLUXDB_BUCKET}")
        |> range(start: -365d)
        |> filter(fn: (r) => r._measurement == "position_closes")
        |> pivot(rowKey:["_time"], columnKey: ["_field"], valueColumn: "_value")
        '''

        tables = query_api.query(flux_query)

        records = []
        for table in tables:
            for record in table.records:
                records.append({
                    "timestamp": record.get_time(),
                    "symbol": record.values.get("symbol"),
                    "side": record.values.get("side"),
                    "pnl": record.values.get("pnl", 0),
                    "entry_price": record.values.get("entry_price", 0),
                    "exit_price": record.values.get("exit_price", 0)
                })

        client.close()

        if len(records) < 50:
            logger.warning(f"⚠️ Only {len(records)} trades found, supplementing with synthetic data")
            df = pd.DataFrame(records)
            synthetic = generate_synthetic_data()
            return pd.concat([df, synthetic], ignore_index=True)

        df = pd.DataFrame(records)
        logger.info(f"✅ Loaded {len(df)} trades from InfluxDB")
        return df

    except Exception as e:
        logger.error(f"❌ InfluxDB error: {e}")
        return generate_synthetic_data()


def generate_synthetic_data(n_samples: int = 500) -> pd.DataFrame:
    """
    Generate synthetic training data for initial model
    This should be replaced with real data once available
    """
    logger.info(f"🔧 Generating {n_samples} synthetic trades for training...")

    np.random.seed(42)

    data = []
    for i in range(n_samples):
        # Generate random features
        side = np.random.choice(["BUY", "SELL"])
        rsi = np.random.uniform(20, 80)
        macd = np.random.uniform(-0.5, 0.5)
        macd_signal = macd + np.random.uniform(-0.2, 0.2)
        ema_alignment = np.random.uniform(-2, 2)
        atr_percent = np.random.uniform(0.5, 3.0)
        volume_ratio = np.random.uniform(0.5, 2.0)
        cvd = np.random.uniform(-1000, 1000)
        adx = np.random.uniform(10, 50)
        price = np.random.uniform(20000, 100000)

        # Calculate "realistic" win probability based on features
        win_prob = 0.5

        # RSI scoring
        if side == "BUY":
            if rsi < 40:
                win_prob += 0.1
            elif rsi > 70:
                win_prob -= 0.15
        else:
            if rsi > 60:
                win_prob += 0.1
            elif rsi < 30:
                win_prob -= 0.15

        # ADX confirmation
        if adx > 25:
            win_prob += 0.15

        # CVD confirmation
        if (side == "BUY" and cvd > 0) or (side == "SELL" and cvd < 0):
            win_prob += 0.1

        # Volume confirmation
        if volume_ratio > 1.2:
            win_prob += 0.05

        # EMA alignment
        if (side == "BUY" and ema_alignment > 0) or (side == "SELL" and ema_alignment < 0):
            win_prob += 0.1

        # Random outcome with weighted probability
        win = 1 if np.random.random() < win_prob else 0
        pnl = np.random.uniform(5, 50) if win else np.random.uniform(-30, -5)

        data.append({
            "side": side,
            "rsi": rsi,
            "macd": macd,
            "macd_signal": macd_signal,
            "ema_alignment": ema_alignment,
            "atr_percent": atr_percent,
            "volume_ratio": volume_ratio,
            "cvd": cvd,
            "adx": adx,
            "price": price,
            "pnl": pnl,
            "win": win
        })

    return pd.DataFrame(data)


def prepare_features(df: pd.DataFrame) -> tuple:
    """
    Prepare feature matrix X and target vector y
    """
    # If we have real data, we need to engineer features
    if "win" not in df.columns:
        df["win"] = (df["pnl"] > 0).astype(int)

    # If we don't have all features, generate them
    required_features = ["rsi", "macd", "macd_signal", "ema_alignment", 
                        "atr_percent", "volume_ratio", "cvd", "adx"]

    for feature in required_features:
        if feature not in df.columns:
            # Generate synthetic feature values
            df[feature] = np.random.uniform(0, 1, len(df))

    # Prepare feature matrix
    X = df[["rsi", "macd", "macd_signal", "ema_alignment", 
            "atr_percent", "volume_ratio", "cvd", "adx"]].copy()

    # Add derived features
    X["macd_histogram"] = X["macd"] - X["macd_signal"]
    X["side_encoded"] = (df["side"] == "BUY").astype(float)

    y = df["win"]

    return X, y


def train_model(X: pd.DataFrame, y: pd.Series) -> XGBClassifier:
    """
    Train XGBoost classifier
    """
    logger.info("🚀 Training XGBoost model...")

    # Split data
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )

    # Initialize model
    model = XGBClassifier(
        n_estimators=100,
        max_depth=5,
        learning_rate=0.1,
        min_child_weight=3,
        subsample=0.8,
        colsample_bytree=0.8,
        random_state=42,
        eval_metric='logloss'
    )

    # Train
    model.fit(
        X_train, y_train,
        eval_set=[(X_test, y_test)],
        verbose=False
    )

    # Evaluate
    y_pred = model.predict(X_test)
    accuracy = accuracy_score(y_test, y_pred)

    logger.info(f"✅ Model trained with {accuracy:.2%} accuracy")
    logger.info("\n📊 Classification Report:")
    logger.info("\n" + classification_report(y_test, y_pred))

    # Feature importance
    feature_importance = pd.DataFrame({
        'feature': X.columns,
        'importance': model.feature_importances_
    }).sort_values('importance', ascending=False)

    logger.info("\n📈 Feature Importance:")
    for _, row in feature_importance.iterrows():
        logger.info(f"  {row['feature']}: {row['importance']:.4f}")

    return model


def main():
    """Main training pipeline"""
    logger.info("=" * 50)
    logger.info("ML Signal Classifier - Training Pipeline")
    logger.info("=" * 50)

    # Fetch data
    df = fetch_trade_data()
    logger.info(f"📊 Total samples: {len(df)}")

    # Prepare features
    X, y = prepare_features(df)
    logger.info(f"📊 Feature shape: {X.shape}")
    logger.info(f"📊 Class distribution: Win={y.sum()}, Loss={len(y)-y.sum()}")

    # Train model
    model = train_model(X, y)

    # Save model
    model_path = "signal_model.pkl"
    joblib.dump(model, model_path)
    logger.info(f"💾 Model saved to {model_path}")

    logger.info("=" * 50)
    logger.info("✅ Training complete!")
    logger.info("=" * 50)


if __name__ == "__main__":
    main()
