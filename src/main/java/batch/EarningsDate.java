package batch;


import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public  class EarningsDate {
        public static void main(String[] args) {
            String inputFilePath = "/Users/baps/Documents/Twillo/SCFiles/earnings.txt"; // Change to your file path
            String outputFilePath = "/Users/baps/Documents/Twillo/SCFiles/parsed_earnings.csv"; // Output CSV file

            try (
                    BufferedReader br = new BufferedReader(new FileReader(inputFilePath));
                    FileWriter writer = new FileWriter(outputFilePath)
            ) {
                String line;
                boolean firstLine = true;

                // Write CSV Header
                writer.write("Date,Time,Session,Symbol\n");

                while ((line = br.readLine()) != null) {
                    if (firstLine) { // Skip header row in input
                        firstLine = false;
                        continue;
                    }

                    String[] parts = line.split("\t"); // Split by tab
                    if (parts.length < 4) continue;

                    String dateTime = parts[0]; // e.g., "8/8/25 16:00"
                    String symbol = parts[1];   // e.g., "TXO"
                    String description = parts[3]; // e.g., "on 8/8/25 After Market Eastern Standard Time"

                    // Extract Date and Time
                    String[] dtParts = dateTime.split(" ");
                    String date = dtParts[0];
                    String time = dtParts[1];

                    // Determine Morning or Evening
                    String session;
                    if (description.toLowerCase().contains("before market")) {
                        session = "Morning";
                    } else if (description.toLowerCase().contains("after market")) {
                        session = "Evening";
                    } else {
                        session = "Unknown";
                    }

                    // Write to CSV file
                    writer.write(String.format("%s,%s,%s,%s\n", date, time, session, symbol));
                }

                System.out.println("Parsing complete! Output saved to: " + outputFilePath);

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    public Map<String, String> getEarningCompanies(LocalDate fromDate, LocalDate toDate) {
        String fileName = "/Users/baps/Documents/Twillo/SCFiles/parsed_earnings.csv";
        Map<String, String> companies = new HashMap<>();

        // CSV format date: M/d/yy
        DateTimeFormatter csvDateFormat = DateTimeFormatter.ofPattern("M/d/yy");

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
                    String dateStr = parts[0].trim();
                    String session = parts[2].trim(); // Already Morning or Evening
                    String symbol = parts[3].trim();

                    // Parse the CSV date
                    LocalDate fileDate = LocalDate.parse(dateStr, csvDateFormat);

                    // Filter only if date is between fromDate and toDate (inclusive)
                    if ((fileDate.isEqual(fromDate) || fileDate.isAfter(fromDate)) &&
                            (fileDate.isEqual(toDate) || fileDate.isBefore(toDate))) {

                        companies.put(symbol, session);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read file: " + e.getMessage());
        }

        return companies;
    }

}
