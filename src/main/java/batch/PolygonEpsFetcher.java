//Step 4
package batch;

import org.json.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class PolygonEpsFetcher {

    public static void main(String[] args) {
        String apiKey = "8CFFkEI2zMfN7xBIkeuJz1qlJ4UJ0iRM"; // Replace with your actual key
        String baseUrl = "https://api.polygon.io/vX/reference/financials";
        String url = baseUrl + "?timeframe=ttm&order=asc&limit=100&sort=filing_date&apiKey=" + apiKey;

        List<String[]> outputRows = new ArrayList<>();
        outputRows.add(new String[]{"Ticker", "Filing Date", "Diluted EPS TTM"});
int x=0;
        try {
            while (url != null) {
                x++;
                URL apiUrl = new URL(url);
                System.out.println("✅Processing"+x);
                HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
                conn.setRequestMethod("GET");

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder responseStr = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    responseStr.append(line);
                }
                reader.close();

                JSONObject responseJson = new JSONObject(responseStr.toString());

                if (responseJson.has("results")) {
                    JSONArray results = responseJson.getJSONArray("results");

                    for (int i = 0; i < results.length(); i++) {
                        JSONObject record = results.getJSONObject(i);

                        // Get ticker
                        JSONArray tickers = record.optJSONArray("tickers");
                        String ticker = (tickers != null && tickers.length() > 0) ? tickers.getString(0) : "N/A";

                        // Get end date
                        String endDate = record.optString("end_date", "N/A");

                        // Get diluted EPS with null-safe checks
                        JSONObject financials = record.optJSONObject("financials");
                        JSONObject incomeStatement = (financials != null) ? financials.optJSONObject("income_statement") : null;
                        JSONObject epsObject = (incomeStatement != null) ? incomeStatement.optJSONObject("diluted_earnings_per_share") : null;

                        String epsValue = (epsObject != null && epsObject.has("value"))
                                ? String.format("%.2f", epsObject.optDouble("value"))
                                : "N/A";

                        outputRows.add(new String[]{ticker, endDate, epsValue});
                    }
                }

                // Handle pagination
                String nextUrl = responseJson.optString("next_url", null);
                if (nextUrl != null && !nextUrl.equals("null")) {
               //     url = "https://api.polygon.io" + nextUrl + "&apiKey=" + apiKey;
                    url = nextUrl+ "&apiKey=" + apiKey;
                    Thread.sleep(1000); // Pause to respect rate limits
                } else {
                    url = null;
                }
            }

            // Write to CSV
            File csvOutput = new File("/Users/baps/Documents/Twillo/SCFiles" +
                    "/diluted_eps_ttm.csv");
            try (PrintWriter pw = new PrintWriter(csvOutput)) {
                outputRows.forEach(row -> pw.println(String.join(",", row)));
            }

            System.out.println("✅ CSV file created: " + csvOutput.getAbsolutePath());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
