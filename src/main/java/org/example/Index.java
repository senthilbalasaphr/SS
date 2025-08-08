package org.example;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Index {

    public static void main(String[] args) {
        Map<String, String> companies = IndexCompanies();

        // Print the HashMap contents
        companies.forEach((company, symbol) ->
                System.out.println("Company: " + company + ", Symbol: " + symbol));
    }

    public static Map<String, String> IndexCompanies() {
      String   fileName = "/Users/baps/Documents/Stocks/ts/indexstock.txt";

        Map<String, String> companies = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t"); // Tab-separated values
                if (parts.length == 2) {
                    String company = parts[0].trim();
                    String symbol = parts[1].trim();
                    companies.put(symbol,company );
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read file: " + e.getMessage());
        }

        return companies;
    }

    public static Map<String, String> EarningCompanies() {
        String fileName = "parsed_earnings.csv"; // Path to your CSV file
        Map<String, String> companies = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                if (firstLine) { // skip header row
                    firstLine = false;
                    continue;
                }

                // CSV format: Date,Time,Session,Symbol
                String[] parts = line.split(",");
                if (parts.length == 4) {
                    String symbol = parts[3].trim();
                    String session = parts[2].trim();
                    companies.put(symbol, session);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read file: " + e.getMessage());
        }

        return companies;
    }
}