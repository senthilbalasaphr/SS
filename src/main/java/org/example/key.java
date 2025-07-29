package org.example;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.security.Key;
import java.util.Properties;

public class key {
    public static String getApiKey(String keyName) {

        Properties props = new Properties();
        String configPath = "/Users/baps/Documents/Twillo/SCFiles/config.properties.txt";

        try (FileReader reader = new FileReader(configPath)) {
            props.load(reader);
            return props.getProperty(keyName);
        } catch (IOException e) {
            System.err.println("❌ Failed to read API key from file: " + e.getMessage());
            return "";
        }
    }

}
