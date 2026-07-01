package com.adaptive.itr.spm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class GenerateRQ4Configs {
    public static void main(String[] args) {
        try {
            // Read the original config file
            String baseContent = new String(Files.readAllBytes(Paths.get("config_D48K.txt")), "UTF-8");

            // Scenario 1: all four weights (Full)
            createConfig(baseContent, "config_RQ4_1_Full.txt", 0.35, 0.35, 0.20, 0.10);

            // Scenario 2: disable TBR (w1 = 0)
            createConfig(baseContent, "config_RQ4_2_wo_TBR.txt", 0.0, 0.50, 0.30, 0.20);

            // Scenario 3: disable Velocity and Recency (w2 = 0, w3 = 0)
            createConfig(baseContent, "config_RQ4_3_wo_V_R.txt", 0.70, 0.0, 0.0, 0.30);

            // Scenario 4: disable Stability (w4 = 0)
            createConfig(baseContent, "config_RQ4_4_wo_S.txt", 0.40, 0.40, 0.20, 0.0);

            System.out.println("[OK] Successfully generated 4 physical config files for RQ4!");

        } catch (IOException e) {
            System.err.println("[ERROR] File read/write error: " + e.getMessage());
        }
    }

    private static void createConfig(String baseContent, String filename, double w1, double w2, double w3, double w4) throws IOException {
        // Use a lowercase regex (w1, w2, ...) to match the fields in your config file
        String newContent = baseContent.replaceAll("(?m)^w1\\s*=.*", "w1=" + w1)
                .replaceAll("(?m)^w2\\s*=.*", "w2=" + w2)
                .replaceAll("(?m)^w3\\s*=.*", "w3=" + w3)
                .replaceAll("(?m)^w4\\s*=.*", "w4=" + w4);

        Files.write(Paths.get(filename), newContent.getBytes("UTF-8"));
    }
}