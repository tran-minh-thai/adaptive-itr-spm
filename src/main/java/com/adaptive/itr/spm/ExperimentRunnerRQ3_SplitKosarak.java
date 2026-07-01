package com.adaptive.itr.spm;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * RQ3 scalability orchestrator - Kosarak size sweep.
 *
 * <p>Kosarak is the largest of the eight datasets (~990k sequences,
 * ~57 MB after SPMF conversion). To characterise how Ada-IncTaSPM's
 * runtime and memory footprint grow with the input size, RQ3 also
 * executes the algorithm on five prefix cuts of Kosarak:
 * 20 %, 40 %, 60 %, 80 % and 100 % of the raw trace.
 *
 * <p>The cuts must exist as {@code kosarak_20.txt}, {@code kosarak_40.txt},
 * ..., {@code kosarak_100.txt} in {@code datasets/}. Instructions for
 * regenerating them from {@code kosarak.txt} are provided in
 * {@code datasets/README.md}.
 *
 * <h2>CLI</h2>
 * <pre>
 *   java ... ExperimentRunnerRQ3_SplitKosarak
 *   java ... ExperimentRunnerRQ3_SplitKosarak --sizes=20,40,60,80,100
 * </pre>
 */
public class ExperimentRunnerRQ3_SplitKosarak {

    /** Percentages of the Kosarak trace swept by default. */
    public static final int[] DEFAULT_DATA_SIZES = {20, 40, 60, 80, 100};

    public static void main(String[] args) {
        int[] dataSizes = CliArgs.getIntList(args, "--sizes", DEFAULT_DATA_SIZES);

        System.out.println("=========================================================");
        System.out.println(" STARTING KOSARAK SCALABILITY SWEEP (RQ3-scale)");
        System.out.println(" Start time: " + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date()));
        System.out.println("=========================================================");

        int successCount = 0;
        for (int size : dataSizes) {
            String configFile = "config_RQ3_kosarak_size_" + size + ".txt";

            if (!AppConfig.configExists(configFile)) {
                System.out.println("\n[SKIP] Config file not found: " + configFile);
                continue;
            }

            System.out.println("\n#########################################################");
            System.out.printf(Locale.US, " RUNNING KOSARAK AT SCALE: %d %%%n", size);
            System.out.printf(Locale.US, " Config: %s%n", configFile);
            System.out.println("#########################################################\n");

            AppConfig.CURRENT_CONFIG_FILE = configFile;
            AppConfig.load();

            runAlgorithm("AdaIncTaSPM", () -> new AdaIncTaSPM().run());

            System.out.println("\n>>> Aggregating results for size = " + size + " % ...");
            ResultAggregator.main(new String[]{});

            ExperimentRunner.clearTempResultFiles();
            successCount++;

            // Extra sleep here compared to RQ1 - the biggest Kosarak cut
            // takes several minutes and benefits from a longer settling
            // window before the next size class kicks off.
            System.gc();
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
        }

        System.out.println("\n=========================================================");
        System.out.println(" KOSARAK SCALABILITY SWEEP COMPLETED.");
        System.out.println(" Success: " + successCount + "/" + dataSizes.length + " scenarios.");
        System.out.println("=========================================================");
    }

    private static void runAlgorithm(String name, Runnable algo) {
        System.out.println(">>> Executing: " + name);
        long startTime = System.currentTimeMillis();
        try {
            algo.run();
        } catch (OutOfMemoryError e) {
            System.err.println("    [FAILED - OOM] the algorithm exceeded the JVM heap at this data size!");
            System.gc();
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        } catch (Exception e) {
            System.err.println("    [FAILED] " + e.getMessage());
            e.printStackTrace();
        }
        long endTime = System.currentTimeMillis();
        System.out.println("    Wall time: " + (endTime - startTime) + " ms");
        System.out.println("---------------------------------------------------------");
    }
}
