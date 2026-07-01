package com.adaptive.itr.spm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Central runtime configuration for every algorithm and experiment runner.
 *
 * <h2>Why static fields?</h2>
 * The six mining algorithms and the four experiment orchestrators all read
 * their tuning knobs (minimum support, target items, adaptive-scoring
 * weights, benchmark repetitions, ...) from a single global. Keeping the
 * fields {@code public static} is a deliberate choice: it lets an
 * orchestrator swap in a new configuration file, call {@link #load()}, and
 * re-run any algorithm without changing constructor signatures.
 *
 * <h2>Configuration file format</h2>
 * A configuration file is a plain-text {@code key=value} document. Blank
 * lines and lines starting with {@code #} are ignored. Recognised keys:
 * <table>
 *   <tr><th>Key</th><th>Effect</th></tr>
 *   <tr><td>{@code dataset_path}</td><td>Input dataset file (resolved via {@link #resolveDataset})</td></tr>
 *   <tr><td>{@code data_chunks}</td><td>Comma-separated fractions of the dataset delivered per incremental batch</td></tr>
 *   <tr><td>{@code min_sup_rate}</td><td>Relative minimum support threshold</td></tr>
 *   <tr><td>{@code max_per}</td><td>Maximum gap (in transactions) allowed between consecutive occurrences for a pattern to remain "regular"</td></tr>
 *   <tr><td>{@code target_items}</td><td>Comma-separated integer ids that must appear in every mined pattern</td></tr>
 *   <tr><td>{@code buffer_ratio}</td><td>Pre-large buffer ratio used by the incremental baselines</td></tr>
 *   <tr><td>{@code eta}</td><td>Amplitude of the adaptive support relaxation in Ada-IncTaSPM</td></tr>
 *   <tr><td>{@code lambda}</td><td>Time-decay coefficient used by the recency term</td></tr>
 *   <tr><td>{@code w1}..{@code w4}</td><td>Weights of the four adaptive-scoring components (volume, target proximity, recency, stability)</td></tr>
 *   <tr><td>{@code total_runs}</td><td>How many end-to-end iterations to execute per config (warm-up + measured)</td></tr>
 *   <tr><td>{@code warmup_runs}</td><td>How many leading iterations to discard from the measurement</td></tr>
 * </table>
 *
 * <h2>Path resolution</h2>
 * {@link #resolveConfig} and {@link #resolveDataset} look up a bare
 * filename in the following order:
 * <ol>
 *   <li>the path itself, if it is absolute or already exists in the working
 *       directory (backwards-compatible with running from the project root);</li>
 *   <li>{@code ${spm.home}/configs} or {@code ${spm.home}/datasets} where
 *       {@code spm.home} is a system property normally set by the launch
 *       scripts to the repository root;</li>
 *   <li>the same {@code configs/} or {@code datasets/} folder relative to the
 *       current working directory;</li>
 *   <li>{@code ../configs/} or {@code ../datasets/}, which is what
 *       {@code run.sh} / {@code run.bat} rely on when the JVM is launched
 *       from {@code results/}.</li>
 * </ol>
 * The result of resolving {@code dataset_path} is written back into
 * {@link #DATASET_PATH} as an absolute path so downstream algorithms do
 * not need to know about the surrounding layout.
 */
public class AppConfig {

    // -----------------------------------------------------------------
    // 1. Config-file pointer (mutated by orchestrators between runs)
    // -----------------------------------------------------------------

    /** Config file to read on the next {@link #load()} call. */
    public static String CURRENT_CONFIG_FILE = "config_D48K.txt";

    // -----------------------------------------------------------------
    // 2. Core mining parameters
    // -----------------------------------------------------------------

    /** Relative minimum-support threshold (fraction of the current transaction count). */
    public static double MIN_SUP_RATE = 0.005;

    /** Maximum allowed gap between consecutive pattern occurrences ("regularity" bound). */
    public static int MAX_PER = 100;

    /** Set of item ids that must appear at least once in every mined pattern. */
    public static Set<Integer> TARGET_ITEMS = new HashSet<>();

    /** Pre-large buffer ratio used by the incremental baselines (Li et al., IncStatic). */
    public static double BUFFER_RATIO = 0.1;

    // -----------------------------------------------------------------
    // 3. Ada-IncTaSPM adaptive-scoring weights
    // -----------------------------------------------------------------

    /** Amplitude of the adaptive support-threshold relaxation, {@code min_sup * (1 - eta * score)}. */
    public static double ETA = 0.05;

    /** Time-decay coefficient used by the recency component. */
    public static double LAMBDA = 0.5;

    /** Weight of the Volume (local support density) component. */
    public static double W1 = 0.2;

    /** Weight of the Target-Proximity component (aka TBR). */
    public static double W2 = 0.4;

    /** Weight of the Recency component. */
    public static double W3 = 0.1;

    /** Weight of the Stability component (Welford variance of inter-arrival gaps). */
    public static double W4 = 0.3;

    // -----------------------------------------------------------------
    // 4. Benchmark repetition control
    // -----------------------------------------------------------------

    /** Total end-to-end iterations executed per config (warm-up + measured). */
    public static int TOTAL_RUNS = 3;

    /** Number of leading iterations to discard from the measurement. */
    public static int WARMUP_RUNS = 2;

    // -----------------------------------------------------------------
    // 5. Input dataset
    // -----------------------------------------------------------------

    /** Absolute path to the currently selected dataset (rewritten by {@link #load()}). */
    public static String DATASET_PATH = "bible.txt";

    /** Fractions of the dataset delivered per incremental batch. Must sum to 1. */
    public static double[] DATA_CHUNKS = {0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1};

    // -----------------------------------------------------------------
    // 6. Path-resolution helpers
    // -----------------------------------------------------------------

    /** Resolve a config-file name by walking the well-known search roots. */
    public static File resolveConfig(String name) {
        return resolveIn(name, "configs");
    }

    /** Resolve a dataset-file name by walking the well-known search roots. */
    public static File resolveDataset(String name) {
        return resolveIn(name, "datasets");
    }

    /** True iff {@link #resolveConfig(String)} finds an existing file. */
    public static boolean configExists(String name) {
        return resolveConfig(name).exists();
    }

    private static File resolveIn(String name, String subdir) {
        if (name == null || name.isEmpty()) return new File(name == null ? "" : name);

        File direct = new File(name);
        if (direct.isAbsolute() || direct.exists()) return direct;

        for (String root : candidateRoots()) {
            File candidate = new File(root, subdir + File.separator + name);
            if (candidate.exists()) return candidate;
            candidate = new File(root, name);
            if (candidate.exists()) return candidate;
        }
        return direct;
    }

    private static String[] candidateRoots() {
        String home = System.getProperty("spm.home", "").trim();
        if (home.isEmpty()) {
            return new String[] {".", "..", System.getProperty("user.dir", ".")};
        }
        return new String[] {home, ".", "..", System.getProperty("user.dir", ".")};
    }

    // -----------------------------------------------------------------
    // 7. Configuration loader
    // -----------------------------------------------------------------

    /**
     * Parse {@link #CURRENT_CONFIG_FILE} and populate the static fields.
     * Unknown keys are silently ignored. Malformed numeric values are
     * reported on {@code stderr} but do not abort the run - the field
     * keeps its previous value.
     */
    public static void load() {
        File configFile = resolveConfig(CURRENT_CONFIG_FILE);
        try (BufferedReader br = new BufferedReader(new FileReader(configFile))) {
            String line;
            TARGET_ITEMS.clear();
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length < 2) continue;
                String key = parts[0].trim().toLowerCase();
                String value = parts[1].trim();
                try {
                    switch (key) {
                        case "min_sup_rate": MIN_SUP_RATE = Double.parseDouble(value); break;
                        case "max_per": MAX_PER = Integer.parseInt(value); break;
                        case "target_items":
                            for (String s : value.split(",")) TARGET_ITEMS.add(Integer.parseInt(s.trim()));
                            break;
                        case "buffer_ratio": BUFFER_RATIO = Double.parseDouble(value); break;
                        case "eta": ETA = Double.parseDouble(value); break;
                        case "lambda": LAMBDA = Double.parseDouble(value); break;
                        case "w1": W1 = Double.parseDouble(value); break;
                        case "w2": W2 = Double.parseDouble(value); break;
                        case "w3": W3 = Double.parseDouble(value); break;
                        case "w4": W4 = Double.parseDouble(value); break;
                        case "total_runs": TOTAL_RUNS = Integer.parseInt(value); break;
                        case "warmup_runs": WARMUP_RUNS = Integer.parseInt(value); break;
                        case "dataset_path":
                            // Rewrite to an absolute path so downstream algorithms
                            // find the dataset regardless of the caller's cwd.
                            File dset = resolveDataset(value);
                            DATASET_PATH = dset.exists() ? dset.getAbsolutePath() : value;
                            break;
                        case "data_chunks":
                            String[] cParts = value.split(",");
                            DATA_CHUNKS = new double[cParts.length];
                            for (int i = 0; i < cParts.length; i++) DATA_CHUNKS[i] = Double.parseDouble(cParts[i].trim());
                            break;
                    }
                } catch (NumberFormatException e) {
                    System.err.println("[AppConfig] Malformed number on line: '" + line
                            + "' in file " + configFile.getPath());
                }
            }
        } catch (Exception e) {
            System.err.println("[AppConfig] Config file not found: " + configFile.getPath()
                    + ". Falling back to defaults.");
        }
    }
}
