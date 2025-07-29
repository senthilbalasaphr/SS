//Step 1
package batch;

import org.example.key;
import org.json.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class Ticket {
  //  private static final String API_KEY_FILE = "/Users/baps/Documents/Twillo/SCFiles/apikey.txt";
    private static final String BASE_URL = "https://api.polygon.io/v3/reference/tickers";
    private static final String DETAIL_URL = "https://api.polygon.io/v3/reference/tickers/";
    private static final String OUTPUT_FILE = "/Users/baps/Documents/Twillo/SCFiles/tickers.csv";

    static String apiKey ="";

    public static void main(String[] args) {
        apiKey = key.getApiKey("polygon.apiKey");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("API key not found in " + apiKey);
            return;
        }

        Map<String, String[]> existingData = readExistingCsv();
        Set<String> knownTickers = existingData.keySet();

        String url = BASE_URL + "?market=stocks&active=true&limit=1000&apiKey=" + apiKey;

        try {
            while (url != null) {
                String jsonResponse = getHttpResponse(url);
                JSONObject json = new JSONObject(jsonResponse);

                JSONArray results = json.optJSONArray("results");
                if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject tickerObj = results.getJSONObject(i);
                        String ticker = tickerObj.optString("ticker");
                        String name = tickerObj.optString("name").replace(",", "\\,");

                        if (!knownTickers.contains(ticker)) {
                            String industry = "";
                            String sicDesc = "";
                            try {
                                String detailUrl = DETAIL_URL + ticker + "?apiKey=" + apiKey;
                                String response = getHttpResponse(detailUrl);
                                JSONObject detail = new JSONObject(response).optJSONObject("results");
                                if (detail != null) {
                                    industry = detail.optString("industry", "");
                                    sicDesc = detail.optString("sic_description", "");
                                }
                                Thread.sleep(200);
                            } catch (Exception e) {
                                System.err.println("Error fetching details for: " + ticker);
                            }
                            existingData.put(ticker, new String[]{name, industry, sicDesc});
                        }
                    }
                }

                String nextUrl = json.optString("next_url", null);
                url = (nextUrl != null && !nextUrl.isEmpty()) ? nextUrl + "&apiKey=" + apiKey : null;
                System.out.println("Progress: " + existingData.size() + " tickers processed so far...");
            }

            writeUpdatedCsv(existingData);
            System.out.println("Final output written to " + OUTPUT_FILE);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    private static Map<String, String[]> readExistingCsv() {
        Map<String, String[]> data = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(OUTPUT_FILE))) {
            String line;
            br.readLine(); // skip header
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",", -1);
                String ticker = parts[0];
                String name = parts.length > 1 ? parts[1] : "";
                String industry = parts.length > 2 ? parts[2] : "";
                String sic = parts.length > 3 ? parts[3] : "";
                data.put(ticker, new String[]{name, industry, sic});
            }
        } catch (IOException e) {
            System.err.println("Error reading CSV: " + e.getMessage());
        }
        return data;
    }

    private static String getHttpResponse(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();
        return response.toString();
    }

    private static void writeUpdatedCsv(Map<String, String[]> data) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE))) {
            writer.write("Symbol,Name,industry,sic_description\n");
            for (Map.Entry<String, String[]> entry : data.entrySet()) {
                String[] values = entry.getValue();
                writer.write(entry.getKey() + "," + values[0] + "," + values[1] + "," + values[2]);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error writing CSV: " + e.getMessage());
        }
    }
}
