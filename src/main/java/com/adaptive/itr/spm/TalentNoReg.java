package com.adaptive.itr.spm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * <b>TalentNoReg</b> - Talent-style projection-based baseline, no
 * regularity enforcement in the search space (V4.17).
 *
 * <p>The Talent family mines sequential patterns using a co-occurrence
 * map (CoocMap) to prune candidate extensions upfront. This
 * implementation separates the map into two matrices so it works
 * correctly on <em>sequences</em>:
 * <ul>
 *   <li><b>S-matrix</b> counts pairs (a, b) where {@code b} appears in
 *       an itemset that comes strictly after the itemset containing
 *       {@code a} (sequential extension).</li>
 *   <li><b>I-matrix</b> counts pairs (a, b) where {@code b} appears in
 *       the same itemset as {@code a} but at a later offset (itemset
 *       extension).</li>
 * </ul>
 * Fusing them into a single map caused two subtle bugs, both of which
 * are fixed here:
 * <ul>
 *   <li>Cross-contamination of S- and I-extensions.</li>
 *   <li>Miscounting of self-repeating patterns such as {@code <(A) (A)>}
 *       along the time axis.</li>
 * </ul>
 *
 * <p>Because Talent is a pure batch miner, the pipeline reloads the
 * entire prefix of the dataset that has arrived so far on every batch;
 * the reported wall time therefore includes the I/O of reading the
 * file from scratch, which is exactly the disadvantage the paper wants
 * to expose against Ada-IncTaSPM.
 */
public class TalentNoReg {

    // ========================================================================
    // 1. DATA STRUCTURES
    // ========================================================================
    private static class ProjectedSeq {
        int sid, tid, off;
        ProjectedSeq(int s, int t, int o) { sid = s; tid = t; off = o; }
    }

    // ========================================================================
    // 2. ALGORITHM STATE FIELDS
    // ========================================================================
    private int min_sup;
    private int maxItemId = 0;
    private int patternCount = 0;

    private int totalItemsBeforePruning = 0;
    private int candidatesAfterPruning = 0;

    private int fullTotalDBSize;
    private int fullTotalTransCount;

    private int[][][] database;
    private int totalDBSize;
    private int[] originalSIDs;
    private int currentOrigTransCount;

    // [FIXED] Separate into two matrices for sequence mining
    private Map<Integer, Map<Integer, Integer>> sCoocMap; // Sequential matrix
    private Map<Integer, Map<Integer, Integer>> iCoocMap; // Itemset matrix

    private boolean enableFileOutput = false;
    private List<String> discoveredPatterns = new ArrayList<>();
    private final String OUTPUT_FILE = "result_TalentNoReg.txt";

    private void resetMiningState() {
        this.patternCount = 0;
        this.totalItemsBeforePruning = 0;
        this.candidatesAfterPruning = 0;
        this.sCoocMap = null;
        this.iCoocMap = null;
        this.database = null;
        this.originalSIDs = null;
        this.totalDBSize = 0;
    }

    // ========================================================================
    // 3. RUN (BENCHMARK FRAMEWORK - PURE BATCH LOAD)
    // ========================================================================
    public void run() {
        AppConfig.load();

        precalculateTotalTransactions(AppConfig.DATASET_PATH);
        if (fullTotalDBSize == 0) return;

        System.out.println("======================================================");
        System.out.println("=== TALENT-NOREG (PURE BATCH W/ TRUE SPM MATRICES) ===");
        System.out.println("======================================================");
        System.out.println("Dataset: " + AppConfig.DATASET_PATH);

        int numBatches = AppConfig.DATA_CHUNKS.length;
        int[] batchEndOrigSids = new int[numBatches];
        double cumulativeRatio = 0.0;

        for (int b = 0; b < numBatches; b++) {
            cumulativeRatio += AppConfig.DATA_CHUNKS[b];
            batchEndOrigSids[b] = (int) Math.round(fullTotalTransCount * cumulativeRatio);
        }
        batchEndOrigSids[numBatches - 1] = fullTotalTransCount;

        double[] avgUpdateTimes = new double[numBatches];
        double[] peakMems = new double[numBatches];
        int[] patternsFound = new int[numBatches];

        this.enableFileOutput = false;
        double sumPeakMemReal = 0.0;
        int countValidMemRuns = 0;

        for (int i = 1; i <= AppConfig.TOTAL_RUNS; i++) {
            boolean isWarmup = (i <= AppConfig.WARMUP_RUNS);
            boolean isLastRun = (i == AppConfig.TOTAL_RUNS);
            boolean canExcludeLast = (AppConfig.TOTAL_RUNS - AppConfig.WARMUP_RUNS) > 1;
            boolean isValidMetricRun = !isWarmup && !(isLastRun && canExcludeLast);

            System.out.println(isWarmup ? "\n--- WARM-UP RUN " + i + " ---" : "\n--- ACTUAL RUN " + (i - AppConfig.WARMUP_RUNS) + " ---");

            double cumulativeTimeMs = 0.0;
            double currentRunPeakMem = 0.0;
            long sumTotalItemsBeforePruning = 0;
            long sumCandidatesAfterPruning = 0;
            int previousTotalSize = 0;

            for (int b = 0; b < numBatches; b++) {
                resetMiningState();
                int endDeltaOrigSid = batchEndOrigSids[b];

                if (isLastRun && b == numBatches - 1) {
                    this.enableFileOutput = true;
                    this.patternCount = 0;
                    this.discoveredPatterns.clear();
                } else {
                    this.enableFileOutput = false;
                }

                System.gc();
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}

                long startTime = System.nanoTime();

                List<int[][]> currentRunDB = new ArrayList<>();
                List<Integer> currentRunSIDs = new ArrayList<>();

                try (BufferedReader br = new BufferedReader(new FileReader(AppConfig.DATASET_PATH))) {
                    readDataFromStart(br, endDeltaOrigSid, currentRunDB, currentRunSIDs);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                setupBatchVariables(endDeltaOrigSid, currentRunDB, currentRunSIDs);
                mineOnDataset();

                long endTime = System.nanoTime();

                double stepTimeMs = (endTime - startTime) / 1_000_000.0;
                cumulativeTimeMs += stepTimeMs;

                Runtime runtime = Runtime.getRuntime();
                double currentPeakMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0);
                currentRunPeakMem = Math.max(currentRunPeakMem, currentPeakMB);

                int currentTotalSize = currentRunDB.size();
                int deltaSize = currentTotalSize - previousTotalSize;

                System.out.printf("  [Batch %d/%d] D_total=%d | ∆D=%d -> Time (incl. I/O): %.2f ms | Cumulative: %.2f ms | RAM: %.2f MB | Patterns: %d%n",
                        (b + 1), numBatches, currentTotalSize, deltaSize, stepTimeMs, cumulativeTimeMs, currentPeakMB, patternCount);

                previousTotalSize = currentTotalSize;

                if (isValidMetricRun) {
                    avgUpdateTimes[b] += cumulativeTimeMs;
                    peakMems[b] = Math.max(peakMems[b], currentPeakMB);
                }

                if (isLastRun) {
                    patternsFound[b] = patternCount;
                    sumTotalItemsBeforePruning += this.totalItemsBeforePruning;
                    sumCandidatesAfterPruning += this.candidatesAfterPruning;
                }
            }

            if (isValidMetricRun) {
                sumPeakMemReal += currentRunPeakMem;
                countValidMemRuns++;
            }

            if (isLastRun) {
                try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(OUTPUT_FILE), StandardCharsets.UTF_8))) {
                    bw.write("MINED PATTERNS AT THE FINAL BATCH:\n");
                    bw.write("======================================================\n");

                    int flushCounter = 0;
                    for (String pat : discoveredPatterns) {
                        bw.write(pat);
                        flushCounter++;
                        if (flushCounter % 5000 == 0) bw.flush();
                    }
                    discoveredPatterns.clear();

                    int lastB = numBatches - 1;
                    int validRuns = Math.max(1, countValidMemRuns);
                    for (int b = 0; b < numBatches; b++) avgUpdateTimes[b] /= validRuns;

                    double maxOverallMem = 0.0;
                    for (double mem : peakMems) if (mem > maxOverallMem) maxOverallMem = mem;

                    double avgItemsBeforePruning = (double) sumTotalItemsBeforePruning / numBatches;
                    double avgCandidatesAfterPruning = (double) sumCandidatesAfterPruning / numBatches;
                    double pruningRatio = (sumTotalItemsBeforePruning > 0) ?
                            (100.0 * (1.0 - (double) sumCandidatesAfterPruning / sumTotalItemsBeforePruning)) : 0.0;

                    int dbSizeOld = (lastB == 0) ? 0 : batchEndOrigSids[lastB - 1];
                    int dbSizeDelta = batchEndOrigSids[lastB] - dbSizeOld;

                    bw.write("\n======================================================\n");
                    bw.write("DETAILED SUMMARY (FINAL BATCH):\n");
                    bw.write("- Dataset: " + AppConfig.DATASET_PATH + "\n");
                    bw.write("- Raw data size: " + fullTotalTransCount + " sequences\n");
                    bw.write("- DB size after target filtering: " + fullTotalDBSize + " sequences\n");
                    bw.write("- Batch splitting ratio (Data chunks): " + Arrays.toString(AppConfig.DATA_CHUNKS) + "\n");
                    bw.write("- General config: min_sup_rate=" + AppConfig.MIN_SUP_RATE + ", max_per=" + AppConfig.MAX_PER + "\n");
                    bw.write("- Parameters: TalentNoReg (Pure Batch + Correct SPM Matrices)\n");
                    bw.write("- Partitions: Dold=" + dbSizeOld + " sequences | ∆D=" + dbSizeDelta + " sequences\n");

                    bw.write("- Total items initialized      : " + sumTotalItemsBeforePruning + " (Average: " + String.format(Locale.US, "%.1f", avgItemsBeforePruning) + " items/batch)\n");
                    bw.write("- Total candidates passing filter     : " + sumCandidatesAfterPruning + " (Average: " + String.format(Locale.US, "%.1f", avgCandidatesAfterPruning) + " candidates/batch)\n");
                    bw.write("- Search-space pruning ratio (avg)  : " + String.format(Locale.US, "%.2f%%\n", pruningRatio));
                    bw.write("- Total patterns: " + patternsFound[lastB] + "\n");

                    double avgPeakMem = sumPeakMemReal / validRuns;
                    bw.write("- Average peak memory: " + String.format(Locale.US, "%.2f MB\n", avgPeakMem));

                    bw.write("- Runtime history (s) across batches : ");
                    for (int step = 0; step < numBatches; step++) {
                        bw.write(String.format(Locale.US, "%.3f", avgUpdateTimes[step] / 1000.0) + (step < numBatches - 1 ? ", " : "\n"));
                    }
                    bw.write("- Peak RAM history (MB) across batches: ");
                    for (int step = 0; step < numBatches; step++) {
                        bw.write(String.format(Locale.US, "%.2f", peakMems[step]) + (step < numBatches - 1 ? ", " : "\n"));
                    }
                    bw.write("- Total cumulative time (Cumulative Update Time): " + String.format(Locale.US, "%.3f s\n", avgUpdateTimes[lastB] / 1000.0));
                    bw.write("- Overall peak memory (Max Update Mem): " + String.format(Locale.US, "%.2f MB\n", maxOverallMem));

                    System.out.println("[OK] File output complete: " + OUTPUT_FILE + "!");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // ========================================================================
    // 4. DATA LOADING METHODS (PURE BATCH)
    // ========================================================================
    private void precalculateTotalTransactions(String path) {
        int cur = 0;
        int validDbSize = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("@")) continue;
                boolean hasT = false, hasItems = false;
                String[] tokens = line.trim().split("\\s+");
                for (String t : tokens) {
                    try {
                        int v = Integer.parseInt(t);
                        if (v == -2) break;
                        if (v == -1) hasItems = true;
                        else {
                            if (AppConfig.TARGET_ITEMS.contains(v)) hasT = true;
                            hasItems = true;
                        }
                    } catch (NumberFormatException e) {}
                }
                if (hasT && hasItems) validDbSize++;
                cur++;
            }
            this.fullTotalDBSize = validDbSize;
            this.fullTotalTransCount = cur;
        } catch (Exception e) {}
    }

    private void readDataFromStart(BufferedReader br, int endDeltaSid, List<int[][]> currentRunDB, List<Integer> currentRunSIDs) throws IOException {
        String line;
        int currentLineIndex = 0;
        while (currentLineIndex < endDeltaSid && (line = br.readLine()) != null) {
            if (line.trim().isEmpty() || line.startsWith("@")) continue;
            String[] tokens = line.trim().split("\\s+");
            List<int[]> seq = new ArrayList<>();
            List<Integer> currentItemset = new ArrayList<>();
            boolean hasTarget = false;

            for (String t : tokens) {
                try {
                    int val = Integer.parseInt(t);
                    if (val == -2) break;
                    else if (val == -1) {
                        if (!currentItemset.isEmpty()) {
                            Collections.sort(currentItemset);
                            seq.add(currentItemset.stream().mapToInt(v -> v).toArray());
                            currentItemset.clear();
                        }
                    } else {
                        currentItemset.add(val);
                        if (val > maxItemId) maxItemId = val;
                        if (AppConfig.TARGET_ITEMS.contains(val)) hasTarget = true;
                    }
                } catch (NumberFormatException e) {}
            }
            if (hasTarget && !seq.isEmpty()) {
                currentRunDB.add(seq.toArray(new int[0][0]));
                currentRunSIDs.add(currentLineIndex);
            }
            currentLineIndex++;
        }
    }

    private void setupBatchVariables(int endDeltaSid, List<int[][]> currentRunDB, List<Integer> currentRunSIDs) {
        this.currentOrigTransCount = endDeltaSid;
        this.totalDBSize = currentRunDB.size();
        this.database = currentRunDB.toArray(new int[0][0][0]);
        this.originalSIDs = currentRunSIDs.stream().mapToInt(v -> v).toArray();
    }

    // ========================================================================
    // 5. MINING ALGORITHM
    // ========================================================================
    private void mineOnDataset() {
        if (totalDBSize == 0) return;
        this.min_sup = Math.max(1, (int) Math.ceil(currentOrigTransCount * AppConfig.MIN_SUP_RATE));

        Map<Integer, Integer> counts = new HashMap<>();
        for (int sid = 0; sid < totalDBSize; sid++) {
            Set<Integer> items = new HashSet<>();
            for (int[] is : database[sid]) for (int it : is) items.add(it);
            for (int it : items) counts.put(it, counts.getOrDefault(it, 0) + 1);
        }

        this.totalItemsBeforePruning = counts.size();

        List<Integer> candidates = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() >= min_sup) candidates.add(e.getKey());
        }
        Collections.sort(candidates);
        this.candidatesAfterPruning = candidates.size();

        // Build the two distinct matrices
        buildCoocMaps(candidates);

        for (int item : candidates) {
            List<ProjectedSeq> pdb = buildInitialPDB(item);
            if (countDistinctSids(pdb) >= min_sup && checkRegularity(pdb)) {
                List<int[]> prefix = new ArrayList<>();
                prefix.add(new int[]{item});
                if (containsTarget(prefix)) {
                    patternCount++;
                    writePatternToFile(prefix, countDistinctSids(pdb));
                }
                dfsTalent(prefix, pdb, candidates);
            }
        }
    }

    // [FIXED] BUILD THE TWO SPM MATRICES (S-MATRIX and I-MATRIX)
    private void buildCoocMaps(List<Integer> candidates) {
        Set<Integer> candSet = new HashSet<>(candidates);
        sCoocMap = new HashMap<>();
        iCoocMap = new HashMap<>();

        for (int sid = 0; sid < totalDBSize; sid++) {
            int[][] seq = database[sid];

            // Use a Long Set to avoid double-counting within the same sequence
            // Bit layout: high 32 bits = Item A, low 32 bits = Item B
            Set<Long> seenS = new HashSet<>();
            Set<Long> seenI = new HashSet<>();

            for (int tid = 0; tid < seq.length; tid++) {
                int[] itemset = seq[tid];

                // 1. Scan I-Extension (same time step)
                for (int i = 0; i < itemset.length; i++) {
                    int a = itemset[i];
                    if (!candSet.contains(a)) continue;
                    for (int j = i + 1; j < itemset.length; j++) {
                        int b = itemset[j];
                        if (!candSet.contains(b)) continue;

                        long key = (((long) a) << 32) | (b & 0xFFFFFFFFL);
                        if (seenI.add(key)) {
                            iCoocMap.computeIfAbsent(a, k -> new HashMap<>()).merge(b, 1, Integer::sum);
                        }
                    }
                }

                // 2. Scan S-Extension (later in time)
                for (int i = 0; i < itemset.length; i++) {
                    int a = itemset[i];
                    if (!candSet.contains(a)) continue;

                    for (int futureTid = tid + 1; futureTid < seq.length; futureTid++) {
                        for (int b : seq[futureTid]) {
                            if (!candSet.contains(b)) continue;

                            // A may equal B (repeated event along the time axis)
                            long key = (((long) a) << 32) | (b & 0xFFFFFFFFL);
                            if (seenS.add(key)) {
                                sCoocMap.computeIfAbsent(a, k -> new HashMap<>()).merge(b, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
        }
    }

    private void dfsTalent(List<int[]> prefix, List<ProjectedSeq> pdb, List<Integer> candidates) {
        int[] lastIsPrefix = prefix.get(prefix.size() - 1);
        int lastItem = lastIsPrefix[lastIsPrefix.length - 1];

        for (int item : candidates) {
            // [FIXED] I-Extension: use iCoocMap (same basket)
            if (item > lastItem && iCoocMap.getOrDefault(lastItem, Collections.emptyMap()).getOrDefault(item, 0) >= min_sup) {
                List<ProjectedSeq> nextPDB = buildIPDB(pdb, item);
                if (countDistinctSids(nextPDB) >= min_sup && checkRegularity(nextPDB)) {
                    List<int[]> newPrefix = clonePrefix(prefix);
                    int[] lastIs = newPrefix.get(newPrefix.size() - 1);
                    int[] newIs = Arrays.copyOf(lastIs, lastIs.length + 1);
                    newIs[newIs.length - 1] = item;
                    newPrefix.set(newPrefix.size() - 1, newIs);
                    if (containsTarget(newPrefix)) {
                        patternCount++;
                        writePatternToFile(newPrefix, countDistinctSids(nextPDB));
                    }
                    dfsTalent(newPrefix, nextPDB, candidates);
                }
            }

            // [FIXED] S-Extension: use sCoocMap (sequential)
            if (sCoocMap.getOrDefault(lastItem, Collections.emptyMap()).getOrDefault(item, 0) >= min_sup) {
                List<ProjectedSeq> nextPDB = buildSPDB(pdb, item);
                if (countDistinctSids(nextPDB) >= min_sup && checkRegularity(nextPDB)) {
                    List<int[]> newPrefix = clonePrefix(prefix);
                    newPrefix.add(new int[]{item});
                    if (containsTarget(newPrefix)) {
                        patternCount++;
                        writePatternToFile(newPrefix, countDistinctSids(nextPDB));
                    }
                    dfsTalent(newPrefix, nextPDB, candidates);
                }
            }
        }
    }

    private List<ProjectedSeq> buildInitialPDB(int item) {
        List<ProjectedSeq> pdb = new ArrayList<>();
        for (int sid = 0; sid < totalDBSize; sid++) {
            int[][] seq = database[sid];
            boolean found = false;
            for (int tid = 0; tid < seq.length && !found; tid++) {
                for (int off = 0; off < seq[tid].length && !found; off++) {
                    if (seq[tid][off] == item) {
                        pdb.add(new ProjectedSeq(sid, tid, off));
                        found = true;
                    }
                }
            }
        }
        return pdb;
    }

    private List<ProjectedSeq> buildIPDB(List<ProjectedSeq> pdb, int item) {
        List<ProjectedSeq> nextPDB = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (ProjectedSeq p : pdb) {
            if (seen.contains(p.sid)) continue;
            int[] is = database[p.sid][p.tid];
            for (int off = p.off + 1; off < is.length; off++) {
                if (is[off] == item) {
                    nextPDB.add(new ProjectedSeq(p.sid, p.tid, off));
                    seen.add(p.sid);
                    break;
                }
            }
        }
        return nextPDB;
    }

    private List<ProjectedSeq> buildSPDB(List<ProjectedSeq> pdb, int item) {
        List<ProjectedSeq> nextPDB = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (ProjectedSeq p : pdb) {
            if (seen.contains(p.sid)) continue;
            int[][] seq = database[p.sid];
            boolean found = false;
            for (int tid = p.tid + 1; tid < seq.length && !found; tid++) {
                for (int off = 0; off < seq[tid].length && !found; off++) {
                    if (seq[tid][off] == item) {
                        nextPDB.add(new ProjectedSeq(p.sid, tid, off));
                        seen.add(p.sid);
                        found = true;
                    }
                }
            }
        }
        return nextPDB;
    }

    private int countDistinctSids(List<ProjectedSeq> pdb) {
        Set<Integer> set = new HashSet<>();
        for (ProjectedSeq p : pdb) set.add(p.sid);
        return set.size();
    }

    private boolean checkRegularity(List<ProjectedSeq> pdb) {
        if (pdb.isEmpty()) return false;
        int maxGap = 0;
        int prev = -1;
        Set<Integer> unique = new TreeSet<>();
        for (ProjectedSeq p : pdb) unique.add(originalSIDs[p.sid]);
        for (int sid : unique) {
            int gap = (prev == -1) ? (sid + 1) : (sid - prev);
            if (gap > maxGap) maxGap = gap;
            prev = sid;
        }
        int tailGap = currentOrigTransCount - prev;
        if (tailGap > maxGap) maxGap = tailGap;
        return maxGap <= AppConfig.MAX_PER;
    }

    private void writePatternToFile(List<int[]> prefix, int support) {
        if (!enableFileOutput) return;
        StringBuilder sb = new StringBuilder();
        sb.append("<");
        for (int k = 0; k < prefix.size(); k++) {
            int[] is = prefix.get(k);
            sb.append("(");
            for (int i = 0; i < is.length; i++) {
                sb.append(is[i]);
                if (i < is.length - 1) sb.append(" ");
            }
            sb.append(")");
            if (k < prefix.size() - 1) sb.append(", ");
        }
        sb.append("> : sup = ").append(support).append("\n");
        discoveredPatterns.add(sb.toString());
    }

    private List<int[]> clonePrefix(List<int[]> prefix) {
        List<int[]> clone = new ArrayList<>(prefix.size());
        for (int[] is : prefix) clone.add(Arrays.copyOf(is, is.length));
        return clone;
    }

    private boolean containsTarget(List<int[]> prefix) {
        for (int[] is : prefix) {
            for (int it : is) {
                if (AppConfig.TARGET_ITEMS.contains(it)) return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        new TalentNoReg().run();
    }
}