// Step 3
package batch;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class AnalyzeHighLow {

    private static final String INPUT_FILE = "/Users/baps/Documents/Twillo/SCFiles/output_365_days.csv";
    private static final String OUTPUT_FILE = "/Users/baps/Documents/Twillo/SCFiles/symbol_high_low_summary.csv";

    public static void main(String[] args) {
        Map<String, SymbolStats> symbolMap = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(INPUT_FILE))) {
            String line = reader.readLine(); // skip header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 8) continue;

                String date = parts[0];
                String ticker = parts[1];
                long volume = Long.parseLong(parts[2]);
                double high = Double.parseDouble(parts[5]);
                double low = Double.parseDouble(parts[6]);

                SymbolStats stats = symbolMap.getOrDefault(ticker, new SymbolStats());
                stats.update(low, high, volume, date);
                symbolMap.put(ticker, stats); // ensure it's in the map
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE))) {
                writer.write("Ticker,Low,LowDate,High,HighDate,AvgVolume\n");
                for (Map.Entry<String, SymbolStats> entry : symbolMap.entrySet()) {
                    SymbolStats s = entry.getValue();
                    long avgVolume = (s.volumeCount > 0) ? (s.totalVolume / s.volumeCount) : 0;

                    writer.write(String.format("%s,%.2f,%s,%.2f,%s,%d\n",
                            entry.getKey(),
                            s.low,
                            s.lowDate,
                            s.high,
                            s.highDate,
                            avgVolume));
                }
            }

            System.out.println("✅ Summary with average volume written to: " + OUTPUT_FILE);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class SymbolStats {
        double low = Double.MAX_VALUE;
        String lowDate = "";
        double high = Double.MIN_VALUE;
        String highDate = "";
        long totalVolume = 0;
        int volumeCount = 0;

        void update(double lowVal, double highVal, long volume, String date) {
            if (lowVal < low) {
                low = lowVal;
                lowDate = date;
            }
            if (highVal > high) {
                high = highVal;
                highDate = date;
            }
            totalVolume += volume;
            volumeCount++;
        }
    }


}
