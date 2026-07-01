package com.adaptive.itr.spm;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * RQ1 / RQ2 orchestrator - multi-dataset benchmark pipeline.
 *
 * <p>For every configuration file listed in {@link #DEFAULT_CONFIG_FILES}
 * (or in the {@code --configs=...} CLI flag), this runner:
 * <ol>
 *   <li>points {@link AppConfig#CURRENT_CONFIG_FILE} at that file and
 *       reloads all knobs;</li>
 *   <li>invokes every algorithm listed in {@link #DEFAULT_ALGORITHMS}
 *       (or in the {@code --algos=...} CLI flag) via
 *       {@link AlgorithmRegistry};</li>
 *   <li>runs {@link ResultAggregator} to merge the per-algorithm
 *       {@code result_*.txt} files into a single {@code Summary_Report}
 *       for the current dataset;</li>
 *   <li>deletes the temporary {@code result_*.txt} files so the next
 *       dataset starts from a clean slate.</li>
 * </ol>
 *
 * <p>Each algorithm invocation is wrapped in a try/catch that swallows
 * {@link OutOfMemoryError} and generic exceptions so a single failing
 * algorithm cannot abort the sweep. This mirrors the behaviour of the
 * original standalone runner: a paper-scale benchmark that finishes
 * partially is far more useful than one that dies on the first OOM.
 *
 * <h2>CLI</h2>
 * <pre>
 *   java ... ExperimentRunner
 *   java ... ExperimentRunner --configs=config_bible.txt,config_kosarak.txt
 *   java ... ExperimentRunner --algos=AdaIncTaSPM,BatchTaSPM
 * </pre>
 */
public class ExperimentRunner {

    /** Configuration files iterated over when no {@code --configs=...} flag is supplied. */
    public static final String[] DEFAULT_CONFIG_FILES = {
            "config_bms1.txt",
            "config_bible.txt",
            "config_kosarak.txt",
            "config_D45K.txt",
            "config_D48K.txt",
            "config_fifa.txt",
            "config_leviathan.txt",
            "config_sign.txt"
    };

    /**
     * Algorithm suite executed when no {@code --algos=...} flag is supplied.
     * The order matches the columns of the Summary_Report table.
     */
    public static final String[] DEFAULT_ALGORITHMS = {
            "AdaIncTaSPM",
            "IncTaSPM_Li",
            "IncStaticTaSPM",
            "TalentNoReg",
            "BatchTaSPM",
            "PrefixSpanTarget"
    };

    public static void main(String[] args) {
        String[] configFiles = CliArgs.getList(args, "--configs", DEFAULT_CONFIG_FILES);
        String[] algorithms  = CliArgs.getList(args, "--algos",   DEFAULT_ALGORITHMS);

        System.out.println("=========================================================");
        System.out.println(" STARTING MULTI-DATASET BENCHMARK (RQ1 / RQ2)");
        System.out.println(" Start time     : " + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date()));
        System.out.println(" Configurations : " + configFiles.length);
        System.out.println(" Algorithms     : " + Arrays.toString(algorithms));
        System.out.println("=========================================================");

        int successCount = 0;
        for (int i = 0; i < configFiles.length; i++) {
            String configFile = configFiles[i];
            if (!AppConfig.configExists(configFile)) {
                System.out.println("\n[SKIP] Config file not found: " + configFile);
                continue;
            }

            System.out.println("\n#########################################################");
            System.out.printf(Locale.US, " RUNNING EXPERIMENT [%d/%d]: %s%n",
                    (i + 1), configFiles.length, configFile);
            System.out.println("#########################################################\n");

            AppConfig.CURRENT_CONFIG_FILE = configFile;
            AppConfig.load();

            for (String algo : algorithms) {
                Runnable r = AlgorithmRegistry.get(algo);
                if (r == null) {
                    System.err.println("    [SKIP] Unknown algorithm: " + algo);
                    continue;
                }
                runAlgorithm(algo, r);
            }

            System.out.println("\n>>> Aggregating results for " + AppConfig.DATASET_PATH + " ...");
            ResultAggregator.main(new String[]{});

            clearTempResultFiles();
            successCount++;

            // Force a GC pause between datasets so the peak-memory number
            // of the next dataset is not inflated by residual objects.
            System.gc();
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        }

        System.out.println("\n=========================================================");
        System.out.println(" BENCHMARK PIPELINE COMPLETED.");
        System.out.println(" Success: " + successCount + "/" + configFiles.length + " datasets.");
        System.out.println(" See Summary_Report_*.txt in the working directory.");
        System.out.println("=========================================================");
    }

    /**
     * Execute one algorithm's {@link Runnable#run()} while suppressing any
     * OOM or runtime exception so that later algorithms still get a chance
     * to run on the same dataset.
     */
    private static void runAlgorithm(String name, Runnable algo) {
        System.out.println(">>> Executing: " + name);
        System.out.println("    Started at: " + new SimpleDateFormat("HH:mm:ss").format(new Date()));
        try {
            algo.run();
        } catch (OutOfMemoryError e) {
            System.err.println("    [FAILED - OUT OF MEMORY] " + name + " exceeded the JVM heap!");
            System.gc();
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        } catch (Exception e) {
            System.err.println("    [FAILED] " + name + " raised: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("---------------------------------------------------------");
    }

    /**
     * Remove the six per-algorithm scratch files produced during a
     * dataset run. Package-private because {@link ExperimentRunnerRQ3}
     * and {@link ExperimentRunnerRQ3_SplitKosarak} share the same
     * cleanup contract.
     */
    static void clearTempResultFiles() {
        String[] files = {
                "result_AdaIncTaSPM.txt",
                "result_IncTaSPM_Li.txt",
                "result_IncStaticTaSPM.txt",
                "result_TalentNoReg.txt",
                "result_BatchTaSPM.txt",
                "result_PrefixSpanTarget.txt"
        };
        for (String f : files) {
            File tempFile = new File(f);
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }
}
