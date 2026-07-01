package com.adaptive.itr.spm;

import java.io.FileWriter;
import java.io.IOException;

public class GenerateRQ3Configs {
    public static void main(String[] args) {
        double[] lambdas = {0.1, 0.3, 0.5, 0.7, 0.9};

        // Two representative datasets
        String[] datasets = {"D48K-C10-T1-N5K.txt", "BMS1_spmf.txt"}; // Rename BMS1.txt to the actual dataset filename
        String[] datasetNames = {"D48K", "BMS1_spmf"};

        for (int i = 0; i < datasets.length; i++) {
            for (double lambda : lambdas) {
                // Example filename: config_RQ3_D48K_lambda_0.1.txt
                String filename = "config_RQ3_" + datasetNames[i] + "_lambda_" + lambda + ".txt";

                String content = "# --- RQ3 configuration: " + datasetNames[i] + " | LAMBDA = " + lambda + " ---\n" +
                        "dataset_path=" + datasets[i] + "\n" +
                        "data_chunks=0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1\n\n" +
                        "min_sup_rate=0.001\n" +
                        "max_per=6000\n" +
                        "target_items=4034276,1762375\n" + // Note: For BMS1 you may need to change target_items to match the actual dataset
                        "buffer_ratio=0.1\n\n" +
                        "W1=0.25\n" +
                        "W2=0.25\n" +
                        "W3=0.25\n" +
                        "W4=0.25\n" +
                        "lambda=" + lambda + "\n";

                try (FileWriter writer = new FileWriter(filename)) {
                    writer.write(content);
                    System.out.println("[OK] Successfully generated: " + filename);
                } catch (IOException e) {
                    System.err.println("[ERROR] Generation error: " + e.getMessage());
                }
            }
        }
    }
}