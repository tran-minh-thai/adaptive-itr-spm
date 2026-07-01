package com.adaptive.itr.spm;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * RQ3 orchestrator - parameter-sensitivity sweep.
 *
 * <p>Ada-IncTaSPM's adaptive-score combines four components (Volume,
 * Target-Proximity, Recency, Stability). The Recency component is
 * controlled by a decay coefficient {@code lambda}. RQ3 studies how
 * sensitive Ada-IncTaSPM is to this decay by rerunning the algorithm
 * over a grid of {@code lambda} values on two representative datasets
 * (a dense synthetic one, D48K, and a sparse click-stream one, BMS1).
 *
 * <p>The runner expects the RQ3 config files to already exist in
 * {@code configs/} - they are produced once by
 * {@link GenerateRQ3Configs} and follow the naming pattern
 * {@code config_RQ3_<dataset>_lambda_<value>.txt}.
 *
 * <h2>CLI</h2>
 * <pre>
 *   java ... ExperimentRunnerRQ3
 *   java ... ExperimentRunnerRQ3 --datasets=D48K,BMS1_spmf --lambdas=0.1,0.3,0.5
 * </pre>
 */
public class ExperimentRunnerRQ3 {

    /** Dataset name-prefixes swept by default (must match the RQ3 config-file naming). */
    public static final String[] DEFAULT_DATASETS = {
            "D48K",
            "BMS1_spmf"
    };

    /** Recency-decay grid swept by default. */
    public static final double[] DEFAULT_LAMBDAS = {0.1, 0.3, 0.5, 0.7, 0.9};

    public static void main(String[] args) {
        String[] datasets = CliArgs.getList(args, "--datasets", DEFAULT_DATASETS);
        double[] lambdas  = CliArgs.getDoubleList(args, "--lambdas", DEFAULT_LAMBDAS);

        System.out.println("=========================================================");
        System.out.println(" STARTING PARAMETER-SENSITIVITY SWEEP (RQ3)");
        System.out.println(" Start time     : " + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date()));
        System.out.println(" Datasets       : " + datasets.length + " " + Arrays.toString(datasets));
        System.out.println(" Lambda values  : " + lambdas.length + " " + Arrays.toString(lambdas));
        System.out.println("=========================================================");

        int successCount = 0;
        int totalScenarios = datasets.length * lambdas.length;

        for (String dataset : datasets) {
            System.out.println("\n=========================================================");
            System.out.println(" PROCESSING DATASET: " + dataset.toUpperCase());
            System.out.println("=========================================================");

            for (double lambda : lambdas) {
                // Compose the RQ3 config filename, e.g. config_RQ3_D48K_lambda_0.1.txt
                String configFile = "config_RQ3_" + dataset + "_lambda_" + lambda + ".txt";

                if (!AppConfig.configExists(configFile)) {
                    System.out.println("  [SKIP] Config file not found: " + configFile);
                    continue;
                }

                System.out.println("\n  #######################################################");
                System.out.printf(Locale.US, "   SCENARIO: lambda = %s%n", lambda);
                System.out.printf(Locale.US, "   Config  : %s%n", configFile);
                System.out.println("  #######################################################\n");

                AppConfig.CURRENT_CONFIG_FILE = configFile;
                AppConfig.load();

                // RQ3 measures only the proposed algorithm; baselines are
                // not sensitive to lambda so they are not swept.
                runAlgorithm("AdaIncTaSPM", () -> new AdaIncTaSPM().run());

                System.out.println("\n  >>> Aggregating results for lambda = " + lambda + " ...");
                ResultAggregator.main(new String[]{});

                ExperimentRunner.clearTempResultFiles();
                successCount++;

                System.gc();
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }

        System.out.println("\n=========================================================");
        System.out.println(" RQ3 SWEEP COMPLETED.");
        System.out.println(" Success: " + successCount + "/" + totalScenarios + " scenarios.");
        System.out.println("=========================================================");
    }

    private static void runAlgorithm(String name, Runnable algo) {
        System.out.println("  >>> Executing: " + name);
        long startTime = System.currentTimeMillis();
        try {
            algo.run();
        } catch (OutOfMemoryError e) {
            System.err.println("      [FAILED] " + name + " exceeded the JVM heap!");
            System.gc();
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        } catch (Exception e) {
            System.err.println("      [FAILED] " + name + " raised: " + e.getMessage());
            e.printStackTrace();
        }
        long endTime = System.currentTimeMillis();
        System.out.println("      Finished in: " + (endTime - startTime) + " ms");
        System.out.println("  -------------------------------------------------------");
    }
}
