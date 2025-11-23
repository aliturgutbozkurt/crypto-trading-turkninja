package com.turkninja;

import com.turkninja.infra.FuturesBinanceService;

public class DebugPositions {
    public static void main(String[] args) {
        System.out.println("Debugging Positions API...");
        try {
            FuturesBinanceService service = new FuturesBinanceService();
            // Force Dry Run to false to check real API if needed, or rely on Config
            // But here we want to read real data, so dry run doesn't matter for GET
            // requests usually,
            // EXCEPT if I added logic to return fake data in dry run.

            // Let's check if dry run affects getPositionInfo
            // In FuturesBinanceService:
            // public String getPositionInfo() {
            // return signedRequest("GET", "/fapi/v2/positionRisk", new LinkedHashMap<>());
            // }
            // It does NOT check dryRun flag. Good.

            String json = service.getPositionInfo();
            System.out.println("Positions JSON Response:");
            System.out.println(json);

            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
