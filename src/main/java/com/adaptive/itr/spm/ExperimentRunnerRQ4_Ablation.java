package com.adaptive.itr.spm;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * RQ4 orchestrator - weight-ablation study.
 *
 * <p>Ada-IncTaSPM's adaptive score is a convex combination of four
 * components (Volume, Target-Proximity, Recency, Stability). RQ4 asks
 * <em>which components actually pay their way</em> by rerunning the
 * algorithm four times on D48K, each time zero-ing out a different
 * subset of the weights:
 *
 * <table>
 *   <tr><th>Scenario</th><th>Config file</th><th>Disabled component(s)</th></tr>
 *   <tr><td>Full</td><td>{@code config_RQ4_1_Full.txt}</td><td>none</td></tr>
 *   <tr><td>-TBR</td><td>{@code config_RQ4_2_TBR.txt}</td><td>w1 = 0 (Volume)</td></tr>
 *   <tr><td>-V,-R</td><td>{@code config_RQ4_3_V_R.txt}</td><td>w2 = w3 = 0 (Target-Proximity, Recency)</td></tr>
 *   <tr><td>-S</td><td>{@code config_RQ4_4_S.txt}</td><td>w4 = 0 (Stability)</td></tr>
 * </table>
 *
 * <p>After each scenario, the generated Summary_Report gets suffixed
 * with the scenario label so all four reports can coexist under
 * {@code results/}.
 *
 * <h2>CLI</h2>
 * <pre>
 *   java ... ExperimentRunnerRQ4_Ablation
 *   java ... ExperimentRunnerRQ4_Ablation --configs=config_RQ4_1_Full.txt
 *                                          --labels=1_Full_Proposed
 * </pre>
 */
public class ExperimentRunnerRQ4_Ablation {

    public static final String[] DEFAULT_CONFIG_FILES = {
            "config_RQ4_1_Full.txt",
            "config_RQ4_2_TBR.txt",
            "config_RQ4_3_V_R.txt",
            "config_RQ4_4_S.txt"
    };

    public static final String[] DEFAULT_SCENARIO_NAMES = {
            "1_Full_Proposed",
            "2_TBR",
            "3_V_and_R",
            "4_S"
    };

    public static void main(String[] args) {
        String[] configFiles   = CliArgs.getList(args, "--configs", DEFAULT_CONFIG_FILES);
        String[] scenarioNames = CliArgs.getList(args, "--labels",  DEFAULT_SCENARIO_NAMES);
        int lim = Math.min(configFiles.length, scenarioNames.length);

        System.out.println("=========================================================");
        System.out.println(" STARTING WEIGHT ABLATION STUDY (RQ4)");
        System.out.println(" Start time: " + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date()));
        System.out.println("=========================================================");

        for (int i = 0; i < lim; i++) {
            String configFile = configFiles[i];
            String scenarioName = scenarioNames[i];

            if (!AppConfig.configExists(configFile)) {
                System.err.println("[SKIP] Config file not found: " + configFile);
                continue;
            }

            System.out.println("\n#########################################################");
            System.out.printf(Locale.US, " >>> SCENARIO [%d/%d]: %s%n", (i + 1), lim, scenarioName);
            System.out.printf(Locale.US, " >>> Config file: %s%n", configFile);

            // Remove stale scratch output so this run does not append to a previous one.
            new File("result_AdaIncTaSPM.txt").delete();

            try {
                AppConfig.CURRENT_CONFIG_FILE = configFile;
                AppConfig.load();

                long startTime = System.currentTimeMillis();
                new AdaIncTaSPM().run();
                long endTime = System.currentTimeMillis();
                System.out.println("      Wall time: " + (endTime - startTime) / 1000.0 + " s");

                ResultAggregator.main(new String[]{});

                // Tag the freshly written Summary_Report so it does not
                // get overwritten by the next ablation scenario.
                renameLatestSummaryReport(scenarioName);

            } catch (OutOfMemoryError e) {
                System.err.println("      [FAILED - OOM] this scenario exceeded the JVM heap!");
            } catch (Exception e) {
                System.err.println("      [FAILED] " + e.getMessage());
            } finally {
                new File("result_AdaIncTaSPM.txt").delete();
                System.gc();
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }

        System.out.println("\n=========================================================");
        System.out.println(" RQ4 ABLATION STUDY COMPLETED.");
        System.out.println("=========================================================");
    }

    /**
     * Locate the most recently modified {@code Summary_Report_*.txt} in
     * the current directory that has not already been tagged (i.e. does
     * not contain {@code [}), and append the scenario label just before
     * the {@code .txt} extension.
     */
    private static void renameLatestSummaryReport(String scenarioName) {
        File dir = new File(".");
        File[] files = dir.listFiles((d, name) -> name.startsWith("Summary_Report_")
                && name.endsWith(".txt") && !name.contains("["));
        if (files != null && files.length > 0) {
            File latest = files[0];
            for (File file : files) {
                if (file.lastModified() > latest.lastModified()) {
                    latest = file;
                }
            }
            String newName = latest.getName().replace(".txt", "_[" + scenarioName + "].txt");
            if (latest.renameTo(new File(newName))) {
                System.out.println("      [OK] Report saved as: " + newName);
            }
        }
    }
}
