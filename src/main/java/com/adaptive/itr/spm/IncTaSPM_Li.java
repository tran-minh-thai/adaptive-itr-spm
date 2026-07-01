package com.adaptive.itr.spm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * <b>IncTaSPM (Li et al.)</b> - Baseline incremental miner that uses the
 * pre-large concept and a backward scan (V4.11).
 *
 * <p>This is the classical incremental target-SPM baseline. When a new
 * batch arrives, an item that was <em>not</em> frequent in the previous
 * database can now become frequent thanks to the additional support
 * from the delta. The pre-large concept keeps a <em>buffer band</em>
 * of items whose support sits below {@link AppConfig#MIN_SUP_RATE}
 * but above a relaxed threshold {@code (1 - buffer_ratio) * min_sup};
 * such items skip the expensive re-scan of the old database on the
 * next update. If, on the other hand, a previously discarded item
 * crosses back into the pre-large band, the algorithm performs a
 * <em>backscan</em> over {@code D_old} to reconstruct its exact support.
 *
 * <p>Improvements retained from the reference implementation:
 * <ul>
 *   <li>Data is streamed batch-by-batch from disk instead of pre-loaded
 *       into RAM, which markedly reduces peak memory usage.</li>
 *   <li>The core mining logic (bitmap intersections + hybrid occurrence
 *       lists) is preserved 100 % - only the I/O plumbing changed.</li>
 * </ul>
 *
 * <p>Compared to Ada-IncTaSPM, this baseline shows the cost of using a
 * <em>fixed</em> relaxation ratio ({@code buffer_ratio}) instead of an
 * adaptive per-item score.
 */
public class IncTaSPM_Li {

    // ========================================================================
    // 1. DATA STRUCTURES (unchanged from the prior version)
    // ========================================================================
    static class HybridBitmap {
        BitSet bits;
        Map<Integer, List<Integer>> occurrences;

        HybridBitmap(int cap) {
            bits = new BitSet(cap);
            occurrences = new HashMap<>();
        }

        HybridBitmap(HybridBitmap other) {
            bits = (BitSet) other.bits.clone();
            occurrences = new HashMap<>();
            for (Map.Entry<Integer, List<Integer>> entry : other.occurrences.entrySet()) {
                occurrences.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }

        void setBit(int sid) { bits.set(sid); }
        void clearBit(int sid) { bits.clear(sid); }
        void addOccurrence(int sid, int tid) {
            occurrences.computeIfAbsent(sid, k -> new ArrayList<>()).add(tid);
        }
        int getTotalSupport() { return bits.cardinality(); }

        HybridBitmap and(HybridBitmap other) {
            HybridBitmap res = new HybridBitmap(bits.size());
            res.bits = (BitSet) bits.clone();
            res.bits.and(other.bits);
            return res;
        }
    }

    static class TimeStepInfo {
        Map<Integer, List<Integer>> occs;
        TimeStepInfo(int capacity) { occs = new HashMap<>(capacity); }
        void add(int sid, List<Integer> positions) { occs.put(sid, positions); }
        List<Integer> getOccurrences(int sid) { return occs.get(sid); }
    }

    // ========================================================================
    // 2. ALGORITHM STATE FIELDS
    // ========================================================================
    private int min_sup;
    private int pre_large_threshold;
    private int maxItemId = 0;
    private int patternCount = 0;

    private int totalItemsBeforePruning = 0;
    private int candidatesAfterPruning = 0;
    private int totalBackscanCount = 0; // Count the number of backward scans over D_old

    // Aggregate counters computed from the initial quick scan
    private int fullTotalDBSize;
    private int fullTotalTransCount;

    // Working database for the current batch (no more fullDatabase)
    private int[][][] database;
    private int[] originalSIDs;
    private int totalDBSize;
    private int dbSizeOld;
    private int dbSizeDelta;
    private int currentOrigTransCount;

    // Cursor tracking the current line while streaming the file
    private int currentLineIndex = 0;

    // Memory carrying the incremental state between batches
    private Map<Integer, Integer> oldExactSupport;
    private Map<Integer, HybridBitmap> oldBitmaps;
    private int old_pre_large_threshold;

    private boolean enableFileOutput = false;
    private BufferedWriter writer = null;
    private List<String> discoveredPatterns = new ArrayList<>();
    private final String OUTPUT_FILE = "result_IncTaSPM_Li.txt";

    // ========================================================================
    // 3. BENCHMARK ORCHESTRATION
    // ========================================================================
    private void resetRunState() {
        this.oldExactSupport = new HashMap<>();
        this.oldBitmaps = new HashMap<>();
        this.old_pre_large_threshold = 0;
        this.totalBackscanCount = 0;
    }

    private void resetBatchState() {
        this.patternCount = 0;
        this.totalItemsBeforePruning = 0;
        this.candidatesAfterPruning = 0;
    }

    public void run() {
        AppConfig.load();

        // 1. Quick file scan to count valid sequences
        precalculateTotalTransactions(AppConfig.DATASET_PATH);
        if (fullTotalDBSize == 0) return;

        System.out.println("======================================================");
        System.out.println("=== INC-TASPM (LI ET AL.) V4.15 - FULL METRICS & RAM DELAY WRITE");
        System.out.println("======================================================");
        System.out.println("Dataset: " + AppConfig.DATASET_PATH);
        System.out.println("Chunks : " + Arrays.toString(AppConfig.DATA_CHUNKS));

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

        // Per-batch backscan history array
        int[] backscansHistory = new int[numBatches];

        this.enableFileOutput = false;

        double sumPeakMemReal = 0.0;
        int countValidMemRuns = 0;

        // --- PHASE 1 & 2: MEASURE AND EXPORT RESULTS ---
        for (int i = 1; i <= AppConfig.TOTAL_RUNS; i++) {
            boolean isWarmup = (i <= AppConfig.WARMUP_RUNS);
            boolean isLastRun = (i == AppConfig.TOTAL_RUNS);
            boolean canExcludeLast = (AppConfig.TOTAL_RUNS - AppConfig.WARMUP_RUNS) > 1;

            boolean isValidMetricRun = !isWarmup && !(isLastRun && canExcludeLast);

            System.out.println(isWarmup ? "\n--- WARM-UP RUN " + i + " ---" : "\n--- ACTUAL RUN " + (i - AppConfig.WARMUP_RUNS) + " ---");

            double cumulativeTimeMs = 0.0;
            double currentRunPeakMem = 0.0;

            // Whole-run cumulative pruning counters
            long sumTotalItemsBeforePruning = 0;
            long sumCandidatesAfterPruning = 0;

            BufferedWriter bw = null;
            resetRunState(); // Reset backscan and pattern state

            try {
                if (isLastRun) {
                    bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(OUTPUT_FILE), StandardCharsets.UTF_8));
                    this.writer = bw;
                    bw.write("MINED PATTERNS AT THE FINAL BATCH:\n");
                    bw.write("======================================================\n");
                }

                try (BufferedReader br = new BufferedReader(new FileReader(AppConfig.DATASET_PATH))) {
                    this.currentLineIndex = 0;
                    List<int[][]> currentRunDB = new ArrayList<>();
                    List<Integer> currentRunSIDs = new ArrayList<>();

                    for (int b = 0; b < numBatches; b++) {
                        resetBatchState(); // Reset totalBackscanCount to 0 at the start of a new batch
                        int endOldOrigSid = (b == 0) ? 0 : batchEndOrigSids[b - 1];
                        int endDeltaOrigSid = batchEndOrigSids[b];

                        readBatchData(br, endDeltaOrigSid, currentRunDB, currentRunSIDs);
                        setupBatchVariables(endOldOrigSid, endDeltaOrigSid, currentRunDB, currentRunSIDs);

                        if (isLastRun && b == numBatches - 1) {
                            this.enableFileOutput = true;
                            this.patternCount = 0;
                            this.discoveredPatterns.clear(); // Clear the in-RAM list before running the final batch
                        } else {
                            this.enableFileOutput = false;
                        }

                        System.gc();
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

                        long startTime = System.nanoTime();
                        runCoreAlgorithm();
                        long endTime = System.nanoTime();

                        double stepTimeMs = (endTime - startTime) / 1_000_000.0;
                        cumulativeTimeMs += stepTimeMs;

                        Runtime runtime = Runtime.getRuntime();
                        double currentPeakMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0);
                        currentRunPeakMem = Math.max(currentRunPeakMem, currentPeakMB);

                        System.out.printf("  [Batch %d/%d] Dold=%d | ∆D=%d -> Time: %.2f ms | Cumulative: %.2f ms | RAM: %.2f MB | Patterns: %d | Backscans: %d%n",
                                (b + 1), numBatches, dbSizeOld, dbSizeDelta, stepTimeMs, cumulativeTimeMs, currentPeakMB, patternCount, totalBackscanCount);

                        if (isValidMetricRun) {
                            avgUpdateTimes[b] += cumulativeTimeMs;
                            peakMems[b] = Math.max(peakMems[b], currentPeakMB);
                        }
                        if (isLastRun) {
                            patternsFound[b] = patternCount;
                            backscansHistory[b] = totalBackscanCount; // Store the per-batch backscan history
                            sumTotalItemsBeforePruning += this.totalItemsBeforePruning;
                            sumCandidatesAfterPruning += this.candidatesAfterPruning;
                        }
                    }
                }

                if (isValidMetricRun) {
                    sumPeakMemReal += currentRunPeakMem;
                    countValidMemRuns++;
                }

                // --- FLUSH ALL RAM-BUFFERED PATTERNS TO DISK IN A SINGLE PASS (TIMEOUT-SAFE) ---
                if (isLastRun && writer != null) {
                    try {
                        for (String pat : discoveredPatterns) {
                            writer.write(pat);
                        }
                        discoveredPatterns.clear(); // Clear the list once flushed
                    } catch (IOException e) { e.printStackTrace(); }
                }

                // APPEND THE SUMMARY TABLE AT THE END OF THE FILE
                if (isLastRun && bw != null) {
                    int lastB = numBatches - 1;
                    int validRuns = Math.max(1, countValidMemRuns);

                    for (int b = 0; b < numBatches; b++) {
                        avgUpdateTimes[b] /= validRuns;
                    }

                    double maxOverallMem = 0.0;
                    for (double mem : peakMems) {
                        if (mem > maxOverallMem) maxOverallMem = mem;
                    }

                    // COMPUTE AVERAGE PRUNING AND BACKSCAN METRICS
                    double avgItemsBeforePruning = (double) sumTotalItemsBeforePruning / numBatches;
                    double avgCandidatesAfterPruning = (double) sumCandidatesAfterPruning / numBatches;
                    double pruningRatio = (sumTotalItemsBeforePruning > 0) ?
                            (100.0 * (1.0 - (double) sumCandidatesAfterPruning / sumTotalItemsBeforePruning)) : 0.0;

                    long totalBackscansAllBatches = 0;
                    for (int bs : backscansHistory) {
                        totalBackscansAllBatches += bs;
                    }
                    double avgBackscans = (double) totalBackscansAllBatches / numBatches;

                    bw.write("\n======================================================\n");
                    bw.write("DETAILED SUMMARY (FINAL BATCH):\n");
                    bw.write("- Dataset: " + AppConfig.DATASET_PATH + "\n");
                    bw.write("- Raw data size: " + fullTotalTransCount + " sequences\n");
                    bw.write("- DB size after target filtering: " + fullTotalDBSize + " sequences\n");
                    bw.write("- Batch splitting ratio (Data chunks): " + Arrays.toString(AppConfig.DATA_CHUNKS) + "\n");
                    bw.write("- General config: min_sup_rate=" + AppConfig.MIN_SUP_RATE + ", max_per=" + AppConfig.MAX_PER + "\n");
                    bw.write("- Li et al. parameters: buffer_ratio=" + AppConfig.BUFFER_RATIO + " (static buffer threshold)\n");
                    bw.write("- Partitions: Dold=" + dbSizeOld + " sequences | ∆D=" + dbSizeDelta + " sequences\n");
                    bw.write("- Internal thresholds: min_sup=" + min_sup + " | pre_large_threshold=" + pre_large_threshold + "\n");

                    // EMIT PRUNING METRICS
                    bw.write("- Total initial items (whole run)      : " + sumTotalItemsBeforePruning + " (Average: " + String.format(Locale.US, "%.1f", avgItemsBeforePruning) + " items/batch)\n");
                    bw.write("- Total buffered patterns after pruning (whole run)   : " + sumCandidatesAfterPruning + " (Average: " + String.format(Locale.US, "%.1f", avgCandidatesAfterPruning) + " patterns/batch)\n");
                    bw.write("- Average pruning ratio (whole run): " + String.format(Locale.US, "%.2f%%\n", pruningRatio));

                    // EMIT BACKSCAN METRICS
                    bw.write("- Total backscan count: " + totalBackscansAllBatches + " times\n");
                    bw.write("- Average backscans per batch: " + String.format(Locale.US, "%.2f", avgBackscans) + " times\n");

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

                    // EMIT BACKSCAN HISTORY ARRAY
                    bw.write("- Backscan history across batches    : ");
                    for (int step = 0; step < numBatches; step++) {
                        bw.write(backscansHistory[step] + (step < numBatches - 1 ? ", " : "\n"));
                    }

                    bw.write("- Total cumulative time (Cumulative Update Time): " + String.format(Locale.US, "%.3f s\n", avgUpdateTimes[lastB] / 1000.0));
                    bw.write("- Overall peak memory (Max Update Mem): " + String.format(Locale.US, "%.2f MB\n", maxOverallMem));

                    System.out.println("[OK] File output complete: " + OUTPUT_FILE + "!");
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (bw != null) {
                    try { bw.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    // ========================================================================
    // 4. INCREMENTAL DATA-LOADING METHODS
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
        } catch (Exception e) {
            System.err.println("Failed to read data file: " + e.getMessage());
        }
    }

    private void readBatchData(BufferedReader br, int endDeltaSid, List<int[][]> currentRunDB, List<Integer> currentRunSIDs) throws IOException {
        String line;
        while (currentLineIndex < endDeltaSid && (line = br.readLine()) != null) {
            if (line.trim().isEmpty() || line.startsWith("@")) continue;

            String[] tokens = line.trim().split("\\s+");
            List<int[]> seq = new ArrayList<>();
            List<Integer> is = new ArrayList<>();
            boolean hasT = false;

            for (String t : tokens) {
                try {
                    int v = Integer.parseInt(t);
                    if (v == -2) break;
                    else if (v == -1) {
                        if (!is.isEmpty()) {
                            Collections.sort(is);
                            seq.add(is.stream().mapToInt(x -> x).toArray());
                            is.clear();
                        }
                    } else {
                        is.add(v);
                        if (AppConfig.TARGET_ITEMS.contains(v)) hasT = true;
                        if (v > maxItemId) maxItemId = v;
                    }
                } catch (NumberFormatException e) {}
            }
            if (hasT && !seq.isEmpty()) {
                currentRunDB.add(seq.toArray(new int[0][0]));
                currentRunSIDs.add(currentLineIndex);
            }
            currentLineIndex++;
        }
    }

    private void setupBatchVariables(int endOldSid, int endDeltaSid, List<int[][]> currentRunDB, List<Integer> currentRunSIDs) {
        this.currentOrigTransCount = endDeltaSid;
        this.totalDBSize = currentRunDB.size();
        this.database = currentRunDB.toArray(new int[0][0][0]);
        this.originalSIDs = currentRunSIDs.stream().mapToInt(v -> v).toArray();

        this.dbSizeOld = 0;
        this.dbSizeDelta = 0;

        for (int i = 0; i < totalDBSize; i++) {
            int origId = this.originalSIDs[i];
            if (origId < endOldSid) {
                this.dbSizeOld++;
            } else if (origId < endDeltaSid) {
                this.dbSizeDelta++;
            }
        }
    }

    // ========================================================================
    // 5. MINING CORE (unchanged from the reference)
    // ========================================================================
    private void runCoreAlgorithm() {
        this.min_sup = Math.max(1, (int) Math.ceil(currentOrigTransCount * AppConfig.MIN_SUP_RATE));
        this.pre_large_threshold = Math.max(1, (int) Math.floor(this.min_sup * (1.0 - AppConfig.BUFFER_RATIO)));

        Map<Integer, Integer> deltaSup = new HashMap<>();
        for (int sid = dbSizeOld; sid < totalDBSize; sid++) {
            Set<Integer> itemsInSeq = new HashSet<>();
            for (int[] itemset : database[sid]) for (int x : itemset) itemsInSeq.add(x);
            for (int item : itemsInSeq) deltaSup.put(item, deltaSup.getOrDefault(item, 0) + 1);
        }

        this.totalItemsBeforePruning = deltaSup.size();

        List<Integer> candidates = new ArrayList<>();
        Map<Integer, HybridBitmap> newItemBitmaps = new HashMap<>();
        Map<Integer, Integer> newExactSupport = new HashMap<>();

        for (Map.Entry<Integer, Integer> entry : deltaSup.entrySet()) {
            int item = entry.getKey();
            int supDelta = entry.getValue();

            if (oldExactSupport.containsKey(item)) {
                int totalSup = oldExactSupport.get(item) + supDelta;
                if (totalSup >= pre_large_threshold) {
                    newExactSupport.put(item, totalSup);
                    candidates.add(item);

                    HybridBitmap bmp = new HybridBitmap(oldBitmaps.get(item));
                    for (int sid = dbSizeOld; sid < totalDBSize; sid++) {
                        for(int t = 0; t < database[sid].length; t++){
                            if(arrayContains(database[sid][t], item)){
                                bmp.setBit(sid); bmp.addOccurrence(sid, t);
                            }
                        }
                    }
                    newItemBitmaps.put(item, bmp);
                }
            } else {
                int upperBound = (old_pre_large_threshold == 0 ? 0 : old_pre_large_threshold - 1) + supDelta;
                if (upperBound >= pre_large_threshold) {
                    this.totalBackscanCount++;
                    int realOldSup = 0;
                    HybridBitmap bmp = new HybridBitmap(totalDBSize);

                    for (int sid = 0; sid < dbSizeOld; sid++) {
                        boolean foundInSid = false;
                        for(int t = 0; t < database[sid].length; t++){
                            if(arrayContains(database[sid][t], item)){
                                bmp.setBit(sid); bmp.addOccurrence(sid, t);
                                foundInSid = true;
                            }
                        }
                        if (foundInSid) realOldSup++;
                    }

                    int trueTotalSup = realOldSup + supDelta;
                    if (trueTotalSup >= pre_large_threshold) {
                        newExactSupport.put(item, trueTotalSup);
                        candidates.add(item);

                        for (int sid = dbSizeOld; sid < totalDBSize; sid++) {
                            for(int t = 0; t < database[sid].length; t++){
                                if(arrayContains(database[sid][t], item)){
                                    bmp.setBit(sid); bmp.addOccurrence(sid, t);
                                }
                            }
                        }
                        newItemBitmaps.put(item, bmp);
                    }
                }
            }
        }

        for (Map.Entry<Integer, Integer> entry : oldExactSupport.entrySet()) {
            int item = entry.getKey();
            if (!deltaSup.containsKey(item)) {
                int oldSup = entry.getValue();
                if (oldSup >= pre_large_threshold) {
                    newExactSupport.put(item, oldSup);
                    candidates.add(item);

                    HybridBitmap bmp = new HybridBitmap(oldBitmaps.get(item));
                    newItemBitmaps.put(item, bmp);
                }
            }
        }

        Collections.sort(candidates);
        this.candidatesAfterPruning = candidates.size();

        for (int item : candidates) {
            HybridBitmap bmp = newItemBitmaps.get(item);
            int sup = newExactSupport.get(item);
            if (bmp != null && sup >= min_sup && checkRegularity(bmp.bits)) {
                List<int[]> prefix = new ArrayList<>();
                prefix.add(new int[]{item});

                if (containsTarget(prefix)) {
                    patternCount++;
                    if (enableFileOutput) writePatternToFile(prefix, sup);
                }

                TimeStepInfo initialTsi = new TimeStepInfo(totalDBSize);
                for (int sid = bmp.bits.nextSetBit(0); sid >= 0; sid = bmp.bits.nextSetBit(sid + 1)) {
                    initialTsi.add(sid, bmp.occurrences.get(sid));
                }

                dfsMine(prefix, bmp, initialTsi, candidates, newItemBitmaps);
            }
        }

        this.oldExactSupport = newExactSupport;
        this.oldBitmaps = newItemBitmaps;
        this.old_pre_large_threshold = this.pre_large_threshold;
    }

    private void dfsMine(List<int[]> prefix, HybridBitmap pBmp, TimeStepInfo pTsi, List<Integer> candidates, Map<Integer, HybridBitmap> itemBitmaps) {
        int[] lastItemset = prefix.get(prefix.size() - 1);
        int lastItem = lastItemset[lastItemset.length - 1];

        for (int item : candidates) {
            HybridBitmap cBmp = itemBitmaps.get(item);
            if (cBmp == null) continue;

            HybridBitmap intersected = pBmp.and(cBmp);
            if (intersected.getTotalSupport() < pre_large_threshold) continue;

            // I-Extension
            if (item > lastItem) {
                HybridBitmap bmpI = new HybridBitmap(intersected);
                TimeStepInfo tsiI = checkIExtension(item, bmpI, pTsi);
                if (tsiI != null && bmpI.getTotalSupport() >= min_sup && checkRegularity(bmpI.bits)) {
                    List<int[]> newPrefix = deepCopy(prefix);
                    int[] newIs = Arrays.copyOf(lastItemset, lastItemset.length + 1);
                    newIs[newIs.length - 1] = item;
                    newPrefix.set(newPrefix.size() - 1, newIs);

                    if (containsTarget(newPrefix)) {
                        patternCount++;
                        if (enableFileOutput) writePatternToFile(newPrefix, bmpI.getTotalSupport());
                    }
                    dfsMine(newPrefix, bmpI, tsiI, candidates, itemBitmaps);
                }
            }

            // S-Extension
            {
                HybridBitmap bmpS = new HybridBitmap(intersected);
                TimeStepInfo tsiS = checkSExtension(item, bmpS, pTsi);
                if (tsiS != null && bmpS.getTotalSupport() >= min_sup && checkRegularity(bmpS.bits)) {
                    List<int[]> newPrefixS = deepCopy(prefix);
                    newPrefixS.add(new int[]{item});

                    if (containsTarget(newPrefixS)) {
                        patternCount++;
                        if (enableFileOutput) writePatternToFile(newPrefixS, bmpS.getTotalSupport());
                    }
                    dfsMine(newPrefixS, bmpS, tsiS, candidates, itemBitmaps);
                }
            }
        }
    }

    private void writePatternToFile(List<int[]> prefix, int support) {
        if (!enableFileOutput) return;

        StringBuilder sb = new StringBuilder(64);
        sb.append("<");
        for (int k = 0; k < prefix.size(); k++) {
            int[] itemset = prefix.get(k);
            sb.append("(");
            for (int i = 0; i < itemset.length; i++) {
                sb.append(itemset[i]);
                if (i < itemset.length - 1) sb.append(" ");
            }
            sb.append(")");
            if (k < prefix.size() - 1) sb.append(", ");
        }
        sb.append("> : sup = ").append(support).append("\n");

        // Only buffer in the in-RAM list; do not flush to disk here to avoid timeouts
        discoveredPatterns.add(sb.toString());
    }

    private TimeStepInfo checkIExtension(int item, HybridBitmap resultBmp, TimeStepInfo pTsi) {
        TimeStepInfo newTsi = new TimeStepInfo(totalDBSize);
        boolean isValid = false;
        for (int sid = resultBmp.bits.nextSetBit(0); sid >= 0; sid = resultBmp.bits.nextSetBit(sid + 1)) {
            List<Integer> pOccurs = pTsi.getOccurrences(sid);
            if (pOccurs == null) { resultBmp.clearBit(sid); continue; }
            List<Integer> validTids = new ArrayList<>();
            for (int pTid : pOccurs) {
                if (arrayContains(database[sid][pTid], item)) validTids.add(pTid);
            }
            if (!validTids.isEmpty()) { newTsi.add(sid, validTids); isValid = true; }
            else resultBmp.clearBit(sid);
        }
        return isValid ? newTsi : null;
    }

    private TimeStepInfo checkSExtension(int item, HybridBitmap resultBmp, TimeStepInfo pTsi) {
        TimeStepInfo newTsi = new TimeStepInfo(totalDBSize);
        boolean isValid = false;
        for (int sid = resultBmp.bits.nextSetBit(0); sid >= 0; sid = resultBmp.bits.nextSetBit(sid + 1)) {
            List<Integer> pOccurs = pTsi.getOccurrences(sid);
            if (pOccurs == null) { resultBmp.clearBit(sid); continue; }
            int firstP = pOccurs.get(0);
            List<Integer> validTids = new ArrayList<>();
            for (int cTid = firstP + 1; cTid < database[sid].length; cTid++) {
                if (arrayContains(database[sid][cTid], item)) validTids.add(cTid);
            }
            if (!validTids.isEmpty()) { newTsi.add(sid, validTids); isValid = true; }
            else resultBmp.clearBit(sid);
        }
        return isValid ? newTsi : null;
    }

    private boolean checkRegularity(BitSet bits) {
        int maxGap = 0, prev = -1;
        for (int sidIdx = bits.nextSetBit(0); sidIdx >= 0; sidIdx = bits.nextSetBit(sidIdx + 1)) {
            int orig = originalSIDs[sidIdx];
            int gap = (prev == -1) ? (orig + 1) : (orig - prev);
            if (gap > maxGap) maxGap = gap;
            prev = orig;
        }
        if (prev == -1) return false;
        int tail = currentOrigTransCount - prev;
        return Math.max(maxGap, tail) <= AppConfig.MAX_PER;
    }

    private boolean arrayContains(int[] itemset, int item) {
        for (int x : itemset) {
            if (x == item) return true;
            if (x > item) break;
        }
        return false;
    }

    private boolean containsTarget(List<int[]> p) {
        for (int[] is : p) for (int i : is) if (AppConfig.TARGET_ITEMS.contains(i)) return true;
        return false;
    }

    private List<int[]> deepCopy(List<int[]> o) {
        List<int[]> c = new ArrayList<>();
        for (int[] a : o) c.add(Arrays.copyOf(a, a.length));
        return c;
    }

    public static void main(String[] args) { new IncTaSPM_Li().run(); }
}