package com.adaptive.itr.spm;

import java.util.Arrays;

/**
 * Unified command-line entry point for the entire benchmark harness.
 *
 * <p>The Ada-IncTaSPM paper reports four groups of experiments:
 * <ul>
 *   <li><b>RQ1 / RQ2</b> - baseline comparison of six algorithms
 *       (Ada-IncTaSPM plus five baselines) across eight public sequence
 *       datasets. Dispatched to {@link ExperimentRunner}.</li>
 *   <li><b>RQ3</b> - parameter sensitivity of Ada-IncTaSPM to the recency
 *       decay {@code lambda} on two representative datasets. Dispatched to
 *       {@link ExperimentRunnerRQ3}.</li>
 *   <li><b>RQ3 (scale)</b> - scalability of Ada-IncTaSPM as increasing
 *       fractions of Kosarak are streamed in. Dispatched to
 *       {@link ExperimentRunnerRQ3_SplitKosarak}.</li>
 *   <li><b>RQ4</b> - ablation of the four adaptive-scoring weights on
 *       D48K. Dispatched to {@link ExperimentRunnerRQ4_Ablation}.</li>
 * </ul>
 * Each research question has its own runner with its own defaults and
 * CLI flags; this class simply routes the command word.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   java -jar adaptive-itr-spm.jar <command> [flags...]
 * }</pre>
 * where {@code <command>} is one of:
 * <table>
 *   <tr><th>Command</th><th>Effect</th></tr>
 *   <tr><td>{@code all}</td><td>Run RQ1/RQ2, RQ3, RQ3-scale and RQ4 back-to-back.</td></tr>
 *   <tr><td>{@code rq1} / {@code rq2} / {@code baseline}</td><td>Baseline comparison.</td></tr>
 *   <tr><td>{@code rq3} / {@code sensitivity}</td><td>Parameter sensitivity.</td></tr>
 *   <tr><td>{@code rq3-scale} / {@code scalability}</td><td>Kosarak scalability.</td></tr>
 *   <tr><td>{@code rq4} / {@code ablation}</td><td>Weight ablation.</td></tr>
 *   <tr><td>{@code generate-rq3}</td><td>Regenerate the RQ3 configuration templates.</td></tr>
 *   <tr><td>{@code generate-rq4}</td><td>Regenerate the RQ4 configuration templates.</td></tr>
 *   <tr><td>{@code aggregate}</td><td>Rebuild a Summary_Report from result_*.txt files in the current directory.</td></tr>
 *   <tr><td>{@code help}</td><td>Print usage.</td></tr>
 * </table>
 *
 * <p>All arguments after the command word are forwarded verbatim to the
 * delegated runner so that per-runner filter flags such as
 * {@code --configs=...}, {@code --algos=...}, {@code --datasets=...},
 * {@code --lambdas=...}, and {@code --sizes=...} can be layered on.
 *
 * <h2>Examples</h2>
 * <pre>{@code
 *   java -jar adaptive-itr-spm.jar all
 *   java -jar adaptive-itr-spm.jar rq1 --configs=config_bible.txt,config_kosarak.txt
 *   java -jar adaptive-itr-spm.jar rq3 --datasets=D48K --lambdas=0.1,0.5
 * }</pre>
 */
public class Main {

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            printHelp();
            return;
        }

        String cmd = args[0].toLowerCase();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (cmd) {
            case "all":
                runAll(rest);
                break;

            case "rq1":
            case "rq2":
            case "baseline":
                ExperimentRunner.main(rest);
                break;

            case "rq3":
            case "sensitivity":
                ExperimentRunnerRQ3.main(rest);
                break;

            case "rq3-scale":
            case "rq3_scale":
            case "scalability":
                ExperimentRunnerRQ3_SplitKosarak.main(rest);
                break;

            case "rq4":
            case "ablation":
                ExperimentRunnerRQ4_Ablation.main(rest);
                break;

            case "generate-rq3":
            case "gen-rq3":
                GenerateRQ3Configs.main(rest);
                break;

            case "generate-rq4":
            case "gen-rq4":
                GenerateRQ4Configs.main(rest);
                break;

            case "aggregate":
                ResultAggregator.main(rest);
                break;

            case "help":
            case "--help":
            case "-h":
                printHelp();
                break;

            default:
                System.err.println("Unknown command: " + cmd);
                System.err.println();
                printHelp();
                System.exit(1);
        }
    }

    private static void runAll(String[] passthrough) {
        banner("PHASE 1/4 - RQ1/RQ2: Baseline comparison");
        ExperimentRunner.main(passthrough);

        banner("PHASE 2/4 - RQ3: Parameter sensitivity");
        ExperimentRunnerRQ3.main(passthrough);

        banner("PHASE 3/4 - RQ3-scale: Kosarak scalability");
        ExperimentRunnerRQ3_SplitKosarak.main(passthrough);

        banner("PHASE 4/4 - RQ4: Ablation");
        ExperimentRunnerRQ4_Ablation.main(passthrough);

        System.out.println();
        System.out.println("=========================================================");
        System.out.println(" ALL EXPERIMENTS COMPLETED.");
        System.out.println("=========================================================");
    }

    private static void banner(String title) {
        System.out.println();
        System.out.println("=========================================================");
        System.out.println(" " + title);
        System.out.println("=========================================================");
    }

    private static void printHelp() {
        System.out.println("Adaptive-ITR-SPM benchmark harness");
        System.out.println();
        System.out.println("Usage: java -jar adaptive-itr-spm.jar <command> [flags...]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  all              Run RQ1/RQ2 + RQ3 + RQ3-scale + RQ4 back-to-back");
        System.out.println("  rq1 | rq2        Baseline comparison across datasets");
        System.out.println("  rq3              Parameter sensitivity");
        System.out.println("  rq3-scale        Kosarak scalability");
        System.out.println("  rq4              Ablation study");
        System.out.println("  generate-rq3     Regenerate RQ3 config files");
        System.out.println("  generate-rq4     Regenerate RQ4 config files");
        System.out.println("  aggregate        Rebuild Summary_Report from result_*.txt in cwd");
        System.out.println("  help             Print this message");
        System.out.println();
        System.out.println("Common flags (forwarded to the underlying runner):");
        System.out.println("  --configs=a.txt,b.txt        Override the list of dataset config files");
        System.out.println("  --algos=AdaIncTaSPM,...      Restrict algorithm suite");
        System.out.println("  --datasets=D48K,BMS1_spmf    (rq3) restrict dataset prefixes");
        System.out.println("  --lambdas=0.1,0.3,0.5        (rq3) restrict lambda grid");
        System.out.println("  --sizes=20,40,60,80,100      (rq3-scale) restrict Kosarak sizes");
        System.out.println();
        System.out.println("Environment / system properties:");
        System.out.println("  -Dspm.home=<dir>             Base directory containing configs/ and datasets/");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar adaptive-itr-spm.jar all");
        System.out.println("  java -jar adaptive-itr-spm.jar rq1 --configs=config_bible.txt");
        System.out.println("  java -jar adaptive-itr-spm.jar rq3 --lambdas=0.1,0.5,0.9");
    }
}
