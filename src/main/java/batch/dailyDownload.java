// Step 2
package batch;

import org.json.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

public class dailyDownload {

    private static final String API_KEY_FILE = "/Users/baps/Documents/Twillo/SCFiles/apikey.txt";
    private static final String TICKERS_FILE = "/Users/baps/Documents/Twillo/SCFiles/tickers.csv";
    private static final String OUTPUT_FILE = "/Users/baps/Documents/Twillo/SCFiles/output_365_days.csv";
    private static final String BASE_URL = "https://api.polygon.io/v2/aggs/grouped/locale/us/market/stocks/";

    public static void main(String[] args) {
        Set<String> symbolSet = readTickerSymbols();
        if (symbolSet.isEmpty()) {
            System.err.println("No symbols loaded from tickers.csv.");
            return;
        }

        String apiKey = readApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("API key missing or empty.");
            return;
        }

        Set<String> processedDates = readExistingDates(OUTPUT_FILE);
        boolean fileExists = new File(OUTPUT_FILE).exists();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE, true))) {
            if (!fileExists) {
                writer.write("Date,Ticker,Volume,Open,Close,High,Low,Transactions\n");
            }

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

            for (int i = 0; i < 365; i++) {
                LocalDate date = LocalDate.now().minusDays(i);
                String dateStr = formatter.format(date);

                if (processedDates.contains(dateStr)) {
                    System.out.println("⏩ Skipping " + dateStr + " (already in file)");
                    continue;
                }

                String url = BASE_URL + dateStr + "?adjusted=true&apiKey=" + apiKey;

                try {
                    String json = getHttpResponse(url);
                    JSONObject obj = new JSONObject(json);
                    JSONArray results = obj.optJSONArray("results");

                    if (results != null) {
                        for (int j = 0; j < results.length(); j++) {
                            JSONObject stock = results.getJSONObject(j);
                            String ticker = stock.optString("T");

                            if (symbolSet.contains(ticker)) {
                                writer.write(String.format("%s,%s,%d,%.2f,%.2f,%.2f,%.2f,%d\n",
                                        dateStr,
                                        ticker,
                                        stock.optLong("v", 0),
                                        stock.optDouble("o", 0),
                                        stock.optDouble("c", 0),
                                        stock.optDouble("h", 0),
                                        stock.optDouble("l", 0),
                                        stock.optLong("n", 0)
                                ));
                            }
                        }
                        System.out.println("✅ " + dateStr + " processed.");
                    }

                    Thread.sleep(100); // avoid hitting API limits

                } catch (Exception e) {
                    System.err.println("❌ Error on " + dateStr + ": " + e.getMessage());
                }
            }

            System.out.println("🎉 Done! Data appended to " + OUTPUT_FILE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String readApiKey() {
        try (BufferedReader reader = new BufferedReader(new FileReader(API_KEY_FILE))) {
            return reader.readLine().trim();
        } catch (IOException e) {
            System.err.println("Failed to read API key: " + e.getMessage());
            return null;
        }
    }

    private static Set<String> readTickerSymbols() {
        Set<String> symbols = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(TICKERS_FILE))) {
            String line;
            reader.readLine(); // skip header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 2);
                if (parts.length > 0) {
                    symbols.add(parts[0].trim());
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading tickers.csv: " + e.getMessage());
        }
        return symbols;
    }

    private static Set<String> readExistingDates(String fileName) {
        Set<String> dates = new HashSet<>();
        File file = new File(fileName);
        if (!file.exists()) return dates;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            reader.readLine(); // skip header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length > 0) {
                    dates.add(parts[0]); // first column = Date
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading existing CSV: " + e.getMessage());
        }

        return dates;
    }

    private static String getHttpResponse(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream())
        );

        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }

        reader.close();
        conn.disconnect();
        return response.toString();
    }
}
