package org.example;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

public class Near52Low {

    public static List<Vector<String>> readNear52LowFromTSV(String filePath, double fromRSI, double toRSI, String symbolFilter) {
        List<Vector<String>> rows = new ArrayList<>();
        final int REQUIRED_COLUMNS = 20;


        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String headerLine = br.readLine(); // Skip header
            if (headerLine == null) return rows;

            String line;
            int lineNumber = 1;

            while ((line = br.readLine()) != null) {
                lineNumber++;

                // Replace problematic expressions
                line = line.replace("custom expression subscription limit exceeded", "N/A");

                // Split
                String[] values = line.split("\t", -1);

                // Pad the values array if it has fewer than 20 columns
                if (values.length < REQUIRED_COLUMNS) {
                    String[] padded = new String[REQUIRED_COLUMNS];
                    System.arraycopy(values, 0, padded, 0, values.length);
                    for (int i = values.length; i < REQUIRED_COLUMNS; i++) {
                        padded[i] = "N/A";
                    }
                    values = padded;
                }

                String symbol = values[0].trim();

                String rsiStr = values[6].replaceAll("[^0-9.]", "");
                double rsi = parseDouble(rsiStr, -1);

                boolean rsiInRange = rsi >= fromRSI && rsi <= toRSI;
                rsiInRange=true;
                boolean symbolMatches = symbolFilter == null || symbolFilter.isEmpty()
                        || symbol.toLowerCase().contains(symbolFilter.toLowerCase());

                if (rsiInRange && symbolMatches) {
                    Vector<String> row = new Vector<>();
                    row.add(values[0]);  // Symbol
                    row.add(values[1]);  // Description
                    row.add(values[2]);  // Last
                    row.add(values[3]);  // Change
                    row.add(values[4]);  // Change %
                    row.add(values[5]);  // Volume
                    if (values[6]=="custom expression subscription limit exceeded"){
                        values[6]= "0";
                    }

                    row.add(values[6]);  // RSI
                    row.add(values[26]);  // Open
                    row.add(values[10]);  // Low
                    row.add(values[9]);  // High

                    float open = parseFloat(values[26], -1f); // Replace with correct column index for Open
                    float dhigh = parseFloat(values[9], -1f); // Replace with correct column index for High
                    float dlow = parseFloat(values[10], -1f);  // Replace with correct column index for Low

                    // Calculate volatility %
                    float vol = 0.0f;
                    float cvol = 0.0f;

                    if (open > 0 && dhigh >= dlow) {
                         vol = ((dhigh - dlow) / open) * 100;
                        cvol = (dhigh - dlow);

                    }
                    row.add(String.format("%.2f", cvol));  // vol
                    row.add(String.format("%.2f", vol));  // vol



                    row.add(values[14]); // 52 Low
                    row.add(values[15]); // 52 High
                    row.add(formatDate(values[16])); // 52 Low Date
                    row.add(formatDate(values[17])); // 52 High Date
                    row.add(values[18]); // Mark
                    row.add(values[19]); // PE







                    // Convert to float
                    float num1 = Float.parseFloat(values[2]);
                    float num2 = Float.parseFloat(values[18]);

                    // Calculate difference
                    float difference = num2 - num1;



                    if (values[18]!=""){
                        float mark = parseFloat(values[18], -1f);
                        float low = parseFloat(values[14], -1f);
                        float high = parseFloat(values[15], -1f);

                        float per = ((mark-low)/(high-low))*100;
                        row.add(String.valueOf(per)); // low high %
                        row.add(String.format("%.2f", difference)); // Industry
                      row.add(values[22]); // Industry
                      row.add(values[23]); // Sub Industry
                        rows.add(row);
                    }

                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return rows;
    }

    public static Vector<String> getNear52LowColumns() {
        Vector<String> columns = new Vector<>();
        columns.add("Symbol");
        columns.add("Description");
        columns.add("Last");
        columns.add("Change");
        columns.add("Change %");
        columns.add("Volume");
        columns.add("RSI");
        columns.add("Open");
        columns.add("Low");
        columns.add("High");
        columns.add("Volatile Amount");
        columns.add("Volatile %");
        columns.add("52 Low");
        columns.add("52 High");
        columns.add("52 Low Date");
        columns.add("52 High Date");
        columns.add("Mark");
        columns.add("PE");
        columns.add("% Low -High");
        columns.add("Difference Last vs Mark");
        columns.add("Industry");
        columns.add("Sub Industry");
        return columns;
    }

    private static double parseDouble(String text, double defaultValue) {
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }


    private static String formatDate(String rawDate) {
        try {
            java.text.SimpleDateFormat input = new java.text.SimpleDateFormat("M/d/yy");
            java.text.SimpleDateFormat output = new java.text.SimpleDateFormat("MM/dd/yyyy");
            return output.format(input.parse(rawDate.trim()));
        } catch (Exception e) {
            return rawDate; // fallback if parsing fails
        }
    }

    private static float parseFloat(String text, float defaultValue) {
        try {
            return Float.parseFloat(text.trim().replaceAll("[^0-9.\\-]", ""));
        } catch (Exception e) {
            return defaultValue;
        }
    }



}
