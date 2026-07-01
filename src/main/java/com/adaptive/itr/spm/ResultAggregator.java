package com.adaptive.itr.spm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Consolidates the six per-algorithm {@code result_*.txt} files produced
 * during one dataset run into a single human-readable Summary_Report.
 *
 * <p>Each algorithm writes its own log to
 * {@code result_<AlgorithmName>.txt} containing:
 * <ul>
 *   <li>the mined patterns at the final batch,</li>
 *   <li>a detailed summary block (parameters, pruning ratios, pattern count),</li>
 *   <li>two arrays of per-batch history: runtime (seconds) and peak RAM (MB).</li>
 * </ul>
 * This class reads all six files (silently skipping any algorithm that
 * failed or was excluded from the sweep) and emits a Summary_Report file
 * containing:
 * <ol>
 *   <li>a runtime comparison table across batches,</li>
 *   <li>a peak-RAM comparison table across batches,</li>
 *   <li>the per-algorithm detailed summary blocks concatenated.</li>
 * </ol>
 * The Summary_Report is what backs the numbers in the paper tables.
 */
public class ResultAggregator {

    public static void main(String[] args) {
        // Reload config from the file selected by ExperimentRunner
        AppConfig.load();

        String[] algos = {
                "AdaIncTaSPM",
                "IncTaSPM_Li",
                "IncStaticTaSPM",
                "TalentNoReg",
                "BatchTaSPM",
                "PrefixSpanTarget"
        };

        int numChunks = AppConfig.DATA_CHUNKS.length;

        // Cache per-batch arrays used to render the tables
        Map<String, String[]> runtimeMap = new LinkedHashMap<>();
        Map<String, String[]> ramMap = new LinkedHashMap<>();

        // Cache the per-algorithm detailed summary blocks
        Map<String, String> finalSummaries = new LinkedHashMap<>();

        for (String algo : algos) {
            String filename = "result_" + algo + ".txt";
            File f = new File(filename);

            // Skip files for algorithms that OOM-ed or did not run
            if (!f.exists()) continue;

            String[] runtimes = new String[numChunks];
            String[] rams = new String[numChunks];

            // Fall back to defaults if the algorithm crashed mid-run
            Arrays.fill(runtimes, "-");
            Arrays.fill(rams, "-");

            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                boolean inSummary = false;
                StringBuilder summaryBuilder = new StringBuilder();

                while ((line = br.readLine()) != null) {
                    // Trigger for reading the detailed summary block at the end of result_*.txt
                    if (line.contains("DETAILED SUMMARY")) inSummary = true;

                    if (inSummary) {
                        summaryBuilder.append(line).append("\n");
                    }

                    // Match lines that carry per-batch history arrays
                    if (line.startsWith("- Runtime history")) {
                        String[] vals = line.split(":")[1].trim().split(",");
                        for (int i = 0; i < Math.min(vals.length, numChunks); i++) runtimes[i] = vals[i].trim();
                    } else if (line.startsWith("- Peak RAM history")) {
                        String[] vals = line.split(":")[1].trim().split(",");
                        for (int i = 0; i < Math.min(vals.length, numChunks); i++) rams[i] = vals[i].trim();
                    }
                }

                runtimeMap.put(algo, runtimes);
                ramMap.put(algo, rams);
                finalSummaries.put(algo, summaryBuilder.toString());

            } catch (Exception e) {
                System.err.println("[WARN] Failed to read result file for algorithm: " + filename);
            }
        }

        // Auto-generate the report filename (e.g. Summary_Report_20260314_153000_bible_10-10-10-10-10-10-10-10-10-10.txt)
        String datasetName = new File(AppConfig.DATASET_PATH).getName().replaceFirst("[.][^.]+$", "");
        StringBuilder chunkBuilder = new StringBuilder();
        for (double c : AppConfig.DATA_CHUNKS) {
            chunkBuilder.append((int) (c * 100)).append("-");
        }
        String chunkStr = chunkBuilder.length() > 0 ? chunkBuilder.substring(0, chunkBuilder.length() - 1) : "100";
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String reportFileName = String.format("Summary_Report_RQ4_%s_%s_%s.txt", timestamp, datasetName, chunkStr);

        // Write the consolidated report file
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(reportFileName), StandardCharsets.UTF_8))) {
            bw.write("==========================================================================================================================================\n");
            bw.write(" SEQUENTIAL PATTERN MINING BENCHMARK - CONSOLIDATED REPORT\n");
            bw.write("==========================================================================================================================================\n");
            bw.write(" Report generated at: " + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date()) + "\n");
            bw.write(" Config file        : " + AppConfig.CURRENT_CONFIG_FILE + "\n");
            bw.write(" Dataset              : " + AppConfig.DATASET_PATH + "\n");
            bw.write(" Batch splitting ratio       : " + Arrays.toString(AppConfig.DATA_CHUNKS) + "\n");
            bw.write("==========================================================================================================================================\n\n");

            // Table 1: runtime
            writeTable(bw, "[1] RUNTIME COMPARISON ACROSS BATCHES (Runtime in seconds)", runtimeMap, numChunks);

            // Table 2: peak RAM
            writeTable(bw, "[2] CUMULATIVE PEAK MEMORY ACROSS BATCHES (Peak RAM in MB)", ramMap, numChunks);

            // Emit the per-algorithm detailed summary block
            bw.write("\n[3] PER-ALGORITHM DETAILED SUMMARY\n");
            bw.write("------------------------------------------------------------------------------------------------------------------------------------------\n");
            for (Map.Entry<String, String> entry : finalSummaries.entrySet()) {
                bw.write(">>> ALGORITHM: " + entry.getKey().toUpperCase() + "\n");
                bw.write(entry.getValue() + "\n");
            }

            System.out.println("[OK] Consolidated report generated: " + reportFileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Helper that renders a per-batch comparison table
     */
    private static void writeTable(BufferedWriter bw, String title, Map<String, String[]> dataMap, int numChunks) throws IOException {
        bw.write(title + "\n");
        bw.write("------------------------------------------------------------------------------------------------------------------------------------------\n");
        bw.write(String.format("%-18s | ", "Algorithm"));
        for (int i = 1; i <= numChunks; i++) {
            bw.write(String.format("Batch %-10d | ", i));
        }
        bw.write("\n------------------------------------------------------------------------------------------------------------------------------------------\n");

        for (Map.Entry<String, String[]> entry : dataMap.entrySet()) {
            bw.write(String.format("%-18s | ", entry.getKey()));
            for (String val : entry.getValue()) {
                bw.write(String.format("%-12s | ", val));
            }
            bw.write("\n");
        }
        bw.write("------------------------------------------------------------------------------------------------------------------------------------------\n\n");
    }
}