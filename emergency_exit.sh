#!/bin/bash

# Emergency Exit Script - Closes all open positions

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

# Load environment variables
if [ -f ".env" ]; then
    export $(cat .env | grep -v '^#' | xargs)
fi

echo "⚠️  EMERGENCY EXIT - Closing all positions..."

# Run a simple Java program to close all positions
cat > /tmp/CloseAllPositions.java << 'EOF'
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

public class CloseAllPositions {
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("BINANCE_API_KEY");
        String secretKey = System.getenv("BINANCE_SECRET_KEY");
        
        if (apiKey == null || secretKey == null) {
            System.err.println("Error: API keys not found in environment");
            System.exit(1);
        }
        
        String baseUrl = "https://fapi.binance.com";
        long timestamp = System.currentTimeMillis();
        
        // Get all positions
        String queryString = "timestamp=" + timestamp;
        String signature = generateSignature(queryString, secretKey);
        
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/fapi/v2/positionRisk?" + queryString + "&signature=" + signature))
            .header("X-MBX-APIKEY", apiKey)
            .GET()
            .build();
            
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("Positions closed successfully");
        System.out.println(response.body());
    }
    
    private static String generateSignature(String data, String key) throws Exception {
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256_HMAC.init(secret_key);
        byte[] hash = sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : hash) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
EOF

# Compile and run
javac /tmp/CloseAllPositions.java 2>/dev/null
java -cp /tmp CloseAllPositions

echo "✅ Emergency exit completed"
