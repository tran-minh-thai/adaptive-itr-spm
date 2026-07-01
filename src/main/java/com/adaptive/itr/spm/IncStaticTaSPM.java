package com.adaptive.itr.spm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * <b>IncStaticTaSPM</b> - Baseline incremental miner with a fixed
 * static-buffer ratio (V4.9).
 *
 * <p>A simpler cousin of {@link IncTaSPM_Li}: keeps the same
 * pre-large concept and static buffer band controlled by
 * {@link AppConfig#BUFFER_RATIO}, but does not attempt to reduce
 * memory pressure by streaming from disk. On every batch it re-projects
 * the entire cumulative database using bitmap intersections, then emits
 * the patterns that satisfy {@link AppConfig#MIN_SUP_RATE} <em>and</em>
 * the {@link AppConfig#MAX_PER} regularity bound.
 *
 * <p>Key points:
 * <ul>
 *   <li>Dedicated {@code buffer_ratio} / {@code pre_large_threshold}
 *       knobs let the paper measure the effect of a static buffer.</li>
 *   <li>Patterns are printed in the canonical form {@code <(1 2), (4)>}
 *       (parenthesised itemsets, comma-separated) so downstream tooling
 *       can parse the output verbatim.</li>
 *   <li>Measurements are cumulative across batches, and the I/O time of
 *       the pattern dump is excluded from the reported runtime.</li>
 * </ul>
 *
 * <p>Compared to Ada-IncTaSPM, IncStaticTaSPM shows the cost of never
 * touching the effective threshold: promising items with a slowly
 * growing support are only rescued if they cross the fixed pre-large
 * band, which typically happens later than an adaptive scheme.
 */
public class IncStaticTaSPM {

    private int min_sup;
    private int pre_large_threshold;
    private int patternCount = 0;

    private int totalItemsBeforePruning = 0;
    private int candidatesAfterPruning = 0;

    private int[][][] fullDatabase;
    private int[] fullOriginalSIDs;
    private int fullTotalDBSize;
    private int fullTotalTransCount;

    private int[][][] database;
    private int[] originalSIDs;
    private int totalDBSize;
    private int dbSizeOld;
    private int dbSizeDelta;
    private int currentOrigTransCount;

    // File-output helper state
    private boolean enableFileOutput = false;
    private BufferedWriter writer = null;
    private final String OUTPUT_FILE = "result_IncStaticTaSPM.txt";
    private List<String> discoveredPatterns = new ArrayList<>();

    private void resetAlgorithmState() {
        this.patternCount = 0;
        this.totalItemsBeforePruning = 0;
        this.candidatesAfterPruning = 0;
    }

    public void run() {
        AppConfig.load();

        loadAllData(AppConfig.DATASET_PATH);
        if (fullTotalDBSize == 0) return;

        System.out.println("======================================================");
        System.out.println("=== INC-STATIC-TASPM (V4.15 - FULL METRICS & RAM CACHE)");
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

            try {
                if (isLastRun) {
                    bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(OUTPUT_FILE), StandardCharsets.UTF_8));
                    this.writer = bw;
                    bw.write("MINED PATTERNS AT THE FINAL BATCH:\n");
                    bw.write("======================================================\n");
                }

                for (int b = 0; b < numBatches; b++) {
                    resetAlgorithmState();
                    int startOrigSid = 0;
                    int endOldOrigSid = (b == 0) ? 0 : batchEndOrigSids[b - 1];
                    int endDeltaOrigSid = batchEndOrigSids[b];

                    prepareBatchDatabase(startOrigSid, endOldOrigSid, endDeltaOrigSid);

                    if (isLastRun && b == numBatches - 1) {
                        this.enableFileOutput = true;
                        this.patternCount = 0;
                        this.discoveredPatterns.clear(); // Clear the RAM buffer before storing new patterns
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

                    System.out.printf("  [Batch %d/%d] Dold=%d | ∆D=%d -> Time: %.2f ms | Cumulative: %.2f ms | RAM: %.2f MB | Patterns: %d%n",
                            (b + 1), numBatches, dbSizeOld, dbSizeDelta, stepTimeMs, cumulativeTimeMs, currentPeakMB, patternCount);

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

                // Flush the in-RAM list to disk in a single I/O to avoid timeouts
                if (isLastRun && writer != null) {
                    try {
                        for (String pat : discoveredPatterns) {
                            writer.write(pat);
                        }
                        discoveredPatterns.clear(); // Clear from RAM
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

                    // COMPUTE AVERAGE PRUNING METRICS
                    double avgItemsBeforePruning = (double) sumTotalItemsBeforePruning / numBatches;
                    double avgCandidatesAfterPruning = (double) sumCandidatesAfterPruning / numBatches;
                    double pruningRatio = (sumTotalItemsBeforePruning > 0) ?
                            (100.0 * (1.0 - (double) sumCandidatesAfterPruning / sumTotalItemsBeforePruning)) : 0.0;

                    bw.write("\n======================================================\n");
                    bw.write("DETAILED SUMMARY (FINAL BATCH):\n");
                    bw.write("- Dataset: " + AppConfig.DATASET_PATH + "\n");
                    bw.write("- Raw data size: " + fullTotalTransCount + " sequences\n");
                    bw.write("- DB size after target filtering: " + fullTotalDBSize + " sequences\n");
                    bw.write("- Batch splitting ratio (Data chunks): " + Arrays.toString(AppConfig.DATA_CHUNKS) + "\n");
                    bw.write("- General config: min_sup_rate=" + AppConfig.MIN_SUP_RATE + ", max_per=" + AppConfig.MAX_PER + "\n");
                    bw.write("- IncStatic parameters: buffer_ratio=" + AppConfig.BUFFER_RATIO + " (static buffer threshold)\n");
                    bw.write("- Partitions: Dold=" + dbSizeOld + " sequences | ∆D=" + dbSizeDelta + " sequences\n");
                    bw.write("- Internal thresholds: min_sup=" + min_sup + " | pre_large_threshold=" + pre_large_threshold + "\n");

                    // NORMALISE TERMINOLOGY FOR ACADEMIC USE
                    bw.write("- Total items initialized      : " + sumTotalItemsBeforePruning + " (Average: " + String.format(Locale.US, "%.1f", avgItemsBeforePruning) + " items/batch)\n");
                    bw.write("- Total candidates passing filter     : " + sumCandidatesAfterPruning + " (Average: " + String.format(Locale.US, "%.1f", avgCandidatesAfterPruning) + " candidates/batch)\n");
                    bw.write("- Search-space pruning ratio (avg)  : " + String.format(Locale.US, "%.2f%%\n", pruningRatio));

                    bw.write("- Total patterns: " + patternsFound[lastB] + "\n");

                    double avgPeakMem = sumPeakMemReal / validRuns;
                    bw.write("- Average peak memory: " + String.format(Locale.US, "%.2f MB\n", avgPeakMem));

                    bw.write("- Runtime history (s) across batches : ");
                    for(int b = 0; b < numBatches; b++) bw.write(String.format(Locale.US, "%.3f", avgUpdateTimes[b] / 1000.0) + (b < numBatches - 1 ? ", " : "\n"));

                    bw.write("- Peak RAM history (MB) across batches: ");
                    for(int b = 0; b < numBatches; b++) bw.write(String.format(Locale.US, "%.2f", peakMems[b]) + (b < numBatches - 1 ? ", " : "\n"));

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

    private void prepareBatchDatabase(int startSid, int endOldSid, int endDeltaSid) {
        this.currentOrigTransCount = endDeltaSid;
        this.dbSizeOld = 0;
        this.dbSizeDelta = 0;

        for (int i = 0; i < fullTotalDBSize; i++) {
            int origId = fullOriginalSIDs[i];
            if (origId >= startSid && origId < endOldSid) this.dbSizeOld++;
            else if (origId >= endOldSid && origId < endDeltaSid) this.dbSizeDelta++;
            else if (origId >= endDeltaSid) break;
        }

        this.totalDBSize = dbSizeOld + dbSizeDelta;
        this.database = new int[totalDBSize][][];
        this.originalSIDs = new int[totalDBSize];

        int idx = 0;
        for (int i = 0; i < fullTotalDBSize; i++) {
            int origId = fullOriginalSIDs[i];
            if (origId >= startSid && origId < endDeltaSid) {
                this.database[idx] = fullDatabase[i];
                this.originalSIDs[idx] = origId;
                idx++;
            }
            if (origId >= endDeltaSid) break;
        }
    }

    private void runCoreAlgorithm() {
        this.min_sup = Math.max(1, (int) Math.ceil(currentOrigTransCount * AppConfig.MIN_SUP_RATE));

        // Static buffer threshold (Pre-large Threshold)
        this.pre_large_threshold = Math.max(1, (int) Math.floor(this.min_sup * (1.0 - AppConfig.BUFFER_RATIO)));

        Map<Integer, BitSet> itemBits = new HashMap<>();
        for (int sid = 0; sid < totalDBSize; sid++) {
            Set<Integer> itemsInSeq = new HashSet<>();
            for (int[] itemset : database[sid]) for (int x : itemset) itemsInSeq.add(x);
            for (int item : itemsInSeq) itemBits.computeIfAbsent(item, k -> new BitSet(totalDBSize)).set(sid);
        }

        this.totalItemsBeforePruning = itemBits.size();

        List<Integer> candidates = new ArrayList<>();
        Map<Integer, HybridBitmap> itemBitmaps = new HashMap<>();

        for (Map.Entry<Integer, BitSet> entry : itemBits.entrySet()) {
            int item = entry.getKey(); BitSet bs = entry.getValue();
            // Filter by the static-buffer threshold instead of min_sup
            if (bs.cardinality() >= pre_large_threshold) {
                HybridBitmap bmp = new HybridBitmap(totalDBSize);
                bmp.bits = bs;
                itemBitmaps.put(item, bmp);
                candidates.add(item);
            }
        }
        Collections.sort(candidates);
        this.candidatesAfterPruning = candidates.size();

        // Attach TID positions for candidates that survive the initial filter
        for (int sid = 0; sid < totalDBSize; sid++) {
            for (int t = 0; t < database[sid].length; t++) {
                for (int item : database[sid][t]) {
                    HybridBitmap bmp = itemBitmaps.get(item);
                    if (bmp != null) bmp.addOccurrence(sid, t);
                }
            }
        }

        for (int item : candidates) {
            HybridBitmap bmp = itemBitmaps.get(item);
            if (bmp != null && bmp.getTotalSupport() >= pre_large_threshold && checkRegularity(bmp.bits)) {
                List<int[]> prefix = new ArrayList<>();
                prefix.add(new int[]{item});

                // A pattern is only accepted and dumped if it reaches MIN_SUP
                if (containsTarget(prefix) && bmp.getTotalSupport() >= min_sup) {
                    patternCount++;
                    if (enableFileOutput) writePatternToFile(prefix, bmp.getTotalSupport());
                }

                TimeStepInfo initialTsi = new TimeStepInfo(totalDBSize);
                for (int sid = bmp.bits.nextSetBit(0); sid >= 0; sid = bmp.bits.nextSetBit(sid + 1)) {
                    initialTsi.add(sid, bmp.occurrences.get(sid));
                }

                dfsMine(prefix, bmp, initialTsi, candidates, itemBitmaps);
            }
        }
    }

    private void dfsMine(List<int[]> prefix, HybridBitmap pBmp, TimeStepInfo pTsi, List<Integer> candidates, Map<Integer, HybridBitmap> itemBitmaps) {
        int[] lastItemset = prefix.get(prefix.size() - 1);
        int lastItem = lastItemset[lastItemset.length - 1];

        for (int item : candidates) {
            HybridBitmap cBmp = itemBitmaps.get(item);
            if (cBmp == null) continue;

            HybridBitmap intersected = pBmp.and(cBmp);

            // PRUNE BY THE STATIC-BUFFER THRESHOLD (PRE-LARGE THRESHOLD)
            if (intersected.getTotalSupport() < pre_large_threshold) continue;

            // I-Extension
            if (item > lastItem) {
                HybridBitmap bmpI = new HybridBitmap(intersected);
                TimeStepInfo tsiI = checkIExtension(item, bmpI, pTsi);
                if (tsiI != null && bmpI.getTotalSupport() >= pre_large_threshold && checkRegularity(bmpI.bits)) {
                    List<int[]> newPrefix = deepCopy(prefix);
                    int[] newIs = Arrays.copyOf(lastItemset, lastItemset.length + 1);
                    newIs[newIs.length - 1] = item;
                    newPrefix.set(newPrefix.size() - 1, newIs);

                    // ONLY COUNT AS A PATTERN WHEN MIN_SUP IS REACHED
                    if (containsTarget(newPrefix) && bmpI.getTotalSupport() >= min_sup) {
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
                if (tsiS != null && bmpS.getTotalSupport() >= pre_large_threshold && checkRegularity(bmpS.bits)) {
                    List<int[]> newPrefixS = deepCopy(prefix);
                    newPrefixS.add(new int[]{item});

                    // ONLY COUNT AS A PATTERN WHEN MIN_SUP IS REACHED
                    if (containsTarget(newPrefixS) && bmpS.getTotalSupport() >= min_sup) {
                        patternCount++;
                        if (enableFileOutput) writePatternToFile(newPrefixS, bmpS.getTotalSupport());
                    }
                    dfsMine(newPrefixS, bmpS, tsiS, candidates, itemBitmaps);
                }
            }
        }
    }

    /**
     * Emit the pattern to disk in the canonical format <(1 2), (4)>
     */
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

        // Do not call writer.write() here. Buffer in RAM so DFS runs at full speed.
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

    public void loadAllData(String path) {
        List<int[][]> tDB = new ArrayList<>();
        List<Integer> tSIDs = new ArrayList<>();
        int cur = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("@")) continue;
                String[] tokens = line.trim().split("\\s+");
                List<int[]> seq = new ArrayList<>();
                List<Integer> is = new ArrayList<>();
                boolean hasT = false;
                for (String t : tokens) {
                    int v = Integer.parseInt(t);
                    if (v == -2) break;
                    else if (v == -1) {
                        if (!is.isEmpty()) { Collections.sort(is); seq.add(is.stream().mapToInt(x->x).toArray()); is.clear(); }
                    } else { is.add(v); if (AppConfig.TARGET_ITEMS.contains(v)) hasT = true; }
                }
                if (hasT && !seq.isEmpty()) { tDB.add(seq.toArray(new int[0][0])); tSIDs.add(cur); }
                cur++;
            }
            fullDatabase = tDB.toArray(new int[0][0][0]);
            fullOriginalSIDs = tSIDs.stream().mapToInt(v -> v).toArray();
            fullTotalDBSize = fullDatabase.length;
            fullTotalTransCount = cur;
        } catch (Exception e) {}
    }

    class HybridBitmap {
        BitSet bits; Map<Integer, List<Integer>> occurrences;
        HybridBitmap(int cap) { bits = new BitSet(cap); occurrences = new HashMap<>(); }
        HybridBitmap(HybridBitmap other) {
            bits = (BitSet) other.bits.clone(); occurrences = new HashMap<>();
        }
        void setBit(int sid) { bits.set(sid); }
        void clearBit(int sid) { bits.clear(sid); }
        void addOccurrence(int sid, int tid) { occurrences.computeIfAbsent(sid, k -> new ArrayList<>()).add(tid); }
        int getTotalSupport() { return bits.cardinality(); }
        HybridBitmap and(HybridBitmap other) {
            HybridBitmap res = new HybridBitmap(bits.size());
            res.bits = (BitSet) bits.clone(); res.bits.and(other.bits);
            return res;
        }
    }

    class TimeStepInfo {
        Map<Integer, List<Integer>> occs;
        TimeStepInfo(int capacity) { occs = new HashMap<>(capacity); }
        void add(int sid, List<Integer> positions) { occs.put(sid, positions); }
        List<Integer> getOccurrences(int sid) { return occs.get(sid); }
    }

    public static void main(String[] args) { new IncStaticTaSPM().run(); }
}