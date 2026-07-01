package com.adaptive.itr.spm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * <b>BatchTaSPM</b> - Static batch baseline for target-based
 * sequential pattern mining.
 *
 * <p>Runs the whole target-SPM pipeline from scratch on every batch:
 * the algorithm re-reads the entire prefix of the dataset that has
 * arrived so far, rebuilds the projected database, then mines
 * patterns using bitmap intersections and Ada-Inc-style projected
 * DBs. There is no incremental state carried between batches.
 *
 * <p>The reported wall time therefore includes both the I/O of
 * reading the file from scratch <em>and</em> the mining time on the
 * cumulative database. This is intentional: the paper needs a
 * baseline that honestly reflects the practical disadvantage of a
 * static miner compared to an incremental one - it is not enough to
 * point out that batch algorithms exist; the reader needs to see the
 * cost.
 *
 * <p>Compared to Ada-IncTaSPM, BatchTaSPM demonstrates the redundant
 * work performed when the algorithm cannot reuse the state built for
 * previous batches.
 */
public class BatchTaSPM {

    // ========================================================================
    // 1. DATA STRUCTURES AND RAW DATA
    // ========================================================================
    private static class RawSequence {
        int sidGoc;
        List<int[]> itemsets;
        RawSequence(int sidGoc) {
            this.sidGoc = sidGoc;
            this.itemsets = new ArrayList<>();
        }
    }

    private static class ProjectedSequence {
        int sidIdx, tidIdx, itemOffset;
        ProjectedSequence(int sidIdx, int tidIdx, int itemOffset) {
            this.sidIdx = sidIdx;
            this.tidIdx = tidIdx;
            this.itemOffset = itemOffset;
        }
    }

    // ========================================================================
    // 2. ALGORITHM STATE FIELDS
    // ========================================================================
    private int min_sup;
    private int maxItemId = 0;
    private int patternCount = 0;
    private int filteredSize = 0;
    private int totalItemsBeforePruning = 0;
    private int[][][] database;
    private int totalDBSize;
    private int currentOrigTransCount;
    private int[] originalSIDs;
    private int fullTotalTransCount;
    private boolean enableFileOutput = false;
    private List<String> discoveredPatterns = new ArrayList<>();
    private final String OUTPUT_FILE = "result_BatchTaSPM.txt";

    private void resetMiningState() {
        this.patternCount = 0;
        this.filteredSize = 0;
        this.totalItemsBeforePruning = 0;
        this.database = null;
        this.originalSIDs = null;
        this.totalDBSize = 0;
    }

    // ========================================================================
    // 3. ORCHESTRATION (PURE BATCH LOAD)
    // ========================================================================
    public void run() {
        AppConfig.load();
        System.out.println("======================================================");
        System.out.println("=== BATCH TASPM (PURE BATCH W/ I/O METRICS)        ===");
        System.out.println("======================================================");

        precalculateTotalTransactions(AppConfig.DATASET_PATH);
        if (fullTotalTransCount == 0) return;

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

        for (int i = 1; i <= AppConfig.TOTAL_RUNS; i++) {
            boolean isWarmup = (i <= AppConfig.WARMUP_RUNS);
            boolean isLastRun = (i == AppConfig.TOTAL_RUNS);
            boolean canExcludeLast = (AppConfig.TOTAL_RUNS - AppConfig.WARMUP_RUNS) > 1;
            boolean isValidMetricRun = !isWarmup && !(isLastRun && canExcludeLast);

            System.out.println(isWarmup ? "\n--- WARM-UP RUN " + i + " ---" : "\n--- ACTUAL RUN " + (i - AppConfig.WARMUP_RUNS) + " ---");

            double cumulativeTimeMs = 0.0;
            double currentRunPeakMem = 0.0;
            long sumTotalItemsBeforePruning = 0;
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

                // START TIMING FROM FILE READ (I/O accumulated)
                long startTime = System.nanoTime();

                // Create a fresh list for each batch
                List<RawSequence> currentRunSequences = new ArrayList<>();

                try (BufferedReader br = new BufferedReader(new FileReader(AppConfig.DATASET_PATH))) {
                    readDataFromStart(br, endDeltaOrigSid, currentRunSequences);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // Mine on the freshly re-loaded dataset
                mineOnDataset(currentRunSequences, endDeltaOrigSid);

                // END TIMING
                long endTime = System.nanoTime();

                double stepTimeMs = (endTime - startTime) / 1_000_000.0;
                cumulativeTimeMs += stepTimeMs;

                Runtime runtime = Runtime.getRuntime();
                double currentPeakMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0);
                currentRunPeakMem = Math.max(currentRunPeakMem, currentPeakMB);

                int currentTotalSize = currentRunSequences.size();
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
                    for (int b = 0; b < numBatches; b++) {
                        avgUpdateTimes[b] /= validRuns;
                    }

                    double maxOverallMem = 0.0;
                    for (double mem : peakMems) {
                        if (mem > maxOverallMem) maxOverallMem = mem;
                    }

                    double avgItemsBeforePruning = (double) sumTotalItemsBeforePruning / numBatches;

                    bw.write("\n======================================================\n");
                    bw.write("DETAILED SUMMARY (FINAL BATCH):\n");
                    bw.write("- Dataset: " + AppConfig.DATASET_PATH + "\n");
                    bw.write("- Raw data size: " + fullTotalTransCount + " sequences\n");
                    bw.write("- DB size after target filtering: " + filteredSize + " sequences (measured on the final batch)\n");
                    bw.write("- Batch splitting ratio (Data chunks): " + Arrays.toString(AppConfig.DATA_CHUNKS) + "\n");
                    bw.write("- Algorithm parameters: BatchTaSPM (Pure Batch - includes I/O cost)\n");
                    bw.write("- Total items initialized      : " + sumTotalItemsBeforePruning + " (Average: " + String.format(Locale.US, "%.1f", avgItemsBeforePruning) + " items/batch)\n");
                    bw.write("- Total patterns: " + patternsFound[lastB] + "\n");

                    double avgPeakMem = sumPeakMemReal / validRuns;
                    bw.write("- Average peak memory: " + String.format(Locale.US, "%.2f MB\n", avgPeakMem));

                    bw.write("- Runtime history (s) across batches : ");
                    for (int b = 0; b < numBatches; b++)
                        bw.write(String.format(Locale.US, "%.3f", avgUpdateTimes[b] / 1000.0) + (b < numBatches - 1 ? ", " : "\n"));

                    bw.write("- Peak RAM history (MB) across batches: ");
                    for (int b = 0; b < numBatches; b++)
                        bw.write(String.format(Locale.US, "%.2f", peakMems[b]) + (b < numBatches - 1 ? ", " : "\n"));

                    bw.write("- Total cumulative time (Cumulative Update Time): " + String.format(Locale.US, "%.3f s\n", avgUpdateTimes[lastB] / 1000.0));
                    bw.write("- Overall peak memory (Max Update Mem): " + String.format(Locale.US, "%.2f MB\n", maxOverallMem));

                    System.out.println("[OK] File output complete: result_BatchTaSPM.txt!");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // ========================================================================
    // 4. DATA-LOADING METHODS (pure batch)
    // ========================================================================
    private void precalculateTotalTransactions(String path) {
        int cur = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("@")) continue;
                cur++;
            }
            this.fullTotalTransCount = cur;
        } catch (Exception e) {
            System.err.println("Failed to read data file: " + e.getMessage());
        }
    }

    private void readDataFromStart(BufferedReader br, int endOrigSid, List<RawSequence> currentRunSequences) throws IOException {
        String line;
        int currentSid = 0; // Always re-read from line 0
        while (currentSid < endOrigSid && (line = br.readLine()) != null) {
            if (line.trim().isEmpty() || line.startsWith("@")) continue;
            String[] tokens = line.trim().split("\\s+");
            RawSequence seq = new RawSequence(currentSid);
            List<Integer> currentItemset = new ArrayList<>();
            for (String t : tokens) {
                try {
                    int val = Integer.parseInt(t);
                    if (val == -2) break;
                    else if (val == -1) {
                        if (!currentItemset.isEmpty()) {
                            Collections.sort(currentItemset);
                            seq.itemsets.add(currentItemset.stream().mapToInt(v -> v).toArray());
                            currentItemset.clear();
                        }
                    } else {
                        currentItemset.add(val);
                        if (val > maxItemId) maxItemId = val;
                    }
                } catch (NumberFormatException e) {
                }
            }
            if (!seq.itemsets.isEmpty()) currentRunSequences.add(seq);
            currentSid++;
        }
    }

    // ========================================================================
    // 5. CORE ALGORITHM (unchanged from the reference)
    // ========================================================================
    private void mineOnDataset(List<RawSequence> dataset, int endOrigSid) {
        this.currentOrigTransCount = endOrigSid;
        buildFilteredDatabase(dataset);
        if (totalDBSize == 0) return;

        this.min_sup = Math.max(1, (int) Math.ceil(currentOrigTransCount * AppConfig.MIN_SUP_RATE));

        Map<Integer, Integer> supportCount = new HashMap<>();
        for (int i = 0; i < totalDBSize; i++) {
            Set<Integer> uniqueItemsInSeq = new HashSet<>();
            for (int[] itemset : database[i]) for (int item : itemset) uniqueItemsInSeq.add(item);
            for (int item : uniqueItemsInSeq) supportCount.put(item, supportCount.getOrDefault(item, 0) + 1);
        }

        this.totalItemsBeforePruning = supportCount.size();
        List<Integer> frequentItems = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : supportCount.entrySet()) {
            if (entry.getValue() >= min_sup) frequentItems.add(entry.getKey());
        }
        Collections.sort(frequentItems);

        List<ProjectedSequence> initialPDB = new ArrayList<>();
        for (int i = 0; i < totalDBSize; i++) initialPDB.add(new ProjectedSequence(i, 0, -1));

        for (int item : frequentItems) {
            List<int[]> prefix = new ArrayList<>();
            prefix.add(new int[]{item});
            List<ProjectedSequence> nextPDB = buildProjectedDB(initialPDB, item, true);
            if (nextPDB.size() >= min_sup && checkRegularity(nextPDB)) {
                if (containsTarget(prefix)) {
                    patternCount++;
                    if (enableFileOutput) writePatternToFile(prefix, getPatternSupport(nextPDB));
                }
                prefixSpanTarget(prefix, nextPDB, frequentItems);
            }
        }
    }

    private void buildFilteredDatabase(List<RawSequence> dataset) {
        List<RawSequence> filtered = new ArrayList<>();
        for (RawSequence seq : dataset) {
            boolean hasTarget = false;
            for (int[] itemset : seq.itemsets) {
                for (int item : itemset) {
                    if (AppConfig.TARGET_ITEMS.contains(item)) {
                        hasTarget = true;
                        break;
                    }
                }
                if (hasTarget) break;
            }
            if (hasTarget) filtered.add(seq);
        }

        this.totalDBSize = filtered.size();
        this.filteredSize = totalDBSize;
        this.database = new int[totalDBSize][][];
        this.originalSIDs = new int[totalDBSize];

        for (int i = 0; i < totalDBSize; i++) {
            RawSequence seq = filtered.get(i);
            this.originalSIDs[i] = seq.sidGoc;
            this.database[i] = seq.itemsets.toArray(new int[0][0]);
        }
    }

    private void prefixSpanTarget(List<int[]> prefix, List<ProjectedSequence> pdb, List<Integer> frequentItems) {
        Map<Integer, Integer> sCount = new HashMap<>();
        Map<Integer, Integer> iCount = new HashMap<>();
        int lastItemInPrefix = prefix.get(prefix.size() - 1)[prefix.get(prefix.size() - 1).length - 1];

        for (ProjectedSequence pseq : pdb) {
            int[][] seq = database[pseq.sidIdx];
            Set<Integer> seenInSidS = new HashSet<>();
            for (int i = pseq.tidIdx + 1; i < seq.length; i++) {
                for (int j = 0; j < seq[i].length; j++) {
                    int item = seq[i][j];
                    if (!seenInSidS.contains(item)) {
                        seenInSidS.add(item);
                        sCount.put(item, sCount.getOrDefault(item, 0) + 1);
                    }
                }
            }
            if (pseq.itemOffset != -1) {
                int[] itemset = seq[pseq.tidIdx];
                Set<Integer> seenInSidI = new HashSet<>();
                for (int j = pseq.itemOffset + 1; j < itemset.length; j++) {
                    int item = itemset[j];
                    if (item > lastItemInPrefix && !seenInSidI.contains(item)) {
                        seenInSidI.add(item);
                        iCount.put(item, iCount.getOrDefault(item, 0) + 1);
                    }
                }
            }
        }

        Set<Integer> validSItems = new HashSet<>();
        for (Map.Entry<Integer, Integer> entry : sCount.entrySet()) {
            if (entry.getValue() >= min_sup) validSItems.add(entry.getKey());
        }

        Set<Integer> validIItems = new HashSet<>();
        for (Map.Entry<Integer, Integer> entry : iCount.entrySet()) {
            if (entry.getValue() >= min_sup) validIItems.add(entry.getKey());
        }

        Map<Integer, List<ProjectedSequence>> sMap = new HashMap<>();
        Map<Integer, List<ProjectedSequence>> iMap = new HashMap<>();

        for (ProjectedSequence pseq : pdb) {
            int[][] seq = database[pseq.sidIdx];
            Set<Integer> seenInSidS = new HashSet<>();
            for (int i = pseq.tidIdx + 1; i < seq.length; i++) {
                for (int j = 0; j < seq[i].length; j++) {
                    int item = seq[i][j];
                    if (validSItems.contains(item) && !seenInSidS.contains(item)) {
                        seenInSidS.add(item);
                        sMap.computeIfAbsent(item, k -> new ArrayList<>()).add(new ProjectedSequence(pseq.sidIdx, i, j));
                    }
                }
            }
            if (pseq.itemOffset != -1) {
                int[] itemset = seq[pseq.tidIdx];
                Set<Integer> seenInSidI = new HashSet<>();
                for (int j = pseq.itemOffset + 1; j < itemset.length; j++) {
                    int item = itemset[j];
                    if (validIItems.contains(item) && !seenInSidI.contains(item)) {
                        seenInSidI.add(item);
                        iMap.computeIfAbsent(item, k -> new ArrayList<>()).add(new ProjectedSequence(pseq.sidIdx, pseq.tidIdx, j));
                    }
                }
            }
        }

        for (int item : frequentItems) {
            if (validIItems.contains(item)) {
                List<ProjectedSequence> nextPDB = iMap.get(item);
                if (nextPDB != null && checkRegularity(nextPDB)) {
                    List<int[]> newPrefix = deepCopySequence(prefix);
                    int[] oldLast = newPrefix.get(newPrefix.size() - 1);
                    int[] newLast = Arrays.copyOf(oldLast, oldLast.length + 1);
                    newLast[newLast.length - 1] = item;
                    newPrefix.set(newPrefix.size() - 1, newLast);
                    if (containsTarget(newPrefix)) {
                        patternCount++;
                        if (enableFileOutput) writePatternToFile(newPrefix, getPatternSupport(nextPDB));
                    }
                    prefixSpanTarget(newPrefix, nextPDB, frequentItems);
                }
            }
            if (validSItems.contains(item)) {
                List<ProjectedSequence> nextPDBS = sMap.get(item);
                if (nextPDBS != null && checkRegularity(nextPDBS)) {
                    List<int[]> newPrefixS = deepCopySequence(prefix);
                    newPrefixS.add(new int[]{item});
                    if (containsTarget(newPrefixS)) {
                        patternCount++;
                        if (enableFileOutput) writePatternToFile(newPrefixS, getPatternSupport(nextPDBS));
                    }
                    prefixSpanTarget(newPrefixS, nextPDBS, frequentItems);
                }
            }
        }
    }

    private List<ProjectedSequence> buildProjectedDB(List<ProjectedSequence> currentPDB, int item, boolean isSExtension) {
        List<ProjectedSequence> nextPDB = new ArrayList<>();
        Set<Integer> processedSids = new HashSet<>();
        for (ProjectedSequence pseq : currentPDB) {
            if (processedSids.contains(pseq.sidIdx)) continue;
            int[][] seq = database[pseq.sidIdx];
            if (isSExtension) {
                boolean found = false;
                for (int i = pseq.tidIdx; i < seq.length; i++) {
                    for (int j = 0; j < seq[i].length; j++) {
                        if (seq[i][j] == item) {
                            nextPDB.add(new ProjectedSequence(pseq.sidIdx, i, j));
                            processedSids.add(pseq.sidIdx);
                            found = true;
                            break;
                        }
                    }
                    if (found) break;
                }
            } else {
                if (pseq.itemOffset != -1) {
                    int[] itemset = seq[pseq.tidIdx];
                    for (int j = pseq.itemOffset + 1; j < itemset.length; j++) {
                        if (itemset[j] == item) {
                            nextPDB.add(new ProjectedSequence(pseq.sidIdx, pseq.tidIdx, j));
                            processedSids.add(pseq.sidIdx);
                            break;
                        }
                    }
                }
            }
        }
        return nextPDB;
    }

    private int getPatternSupport(List<ProjectedSequence> pdb) {
        Set<Integer> uniqueSids = new HashSet<>();
        for (ProjectedSequence pseq : pdb) uniqueSids.add(pseq.sidIdx);
        return uniqueSids.size();
    }

    private boolean checkRegularity(List<ProjectedSequence> pdb) {
        if (pdb.isEmpty()) return false;
        int maxGap = 0;
        int prevOrigSid = -1;
        Set<Integer> uniqueOrigSids = new TreeSet<>();
        for (ProjectedSequence p : pdb) uniqueOrigSids.add(originalSIDs[p.sidIdx]);
        for (int origSid : uniqueOrigSids) {
            int gap = (prevOrigSid == -1) ? (origSid + 1) : (origSid - prevOrigSid);
            if (gap > maxGap) maxGap = gap;
            prevOrigSid = origSid;
        }
        int tailGap = currentOrigTransCount - prevOrigSid;
        if (tailGap > maxGap) maxGap = tailGap;
        return maxGap <= AppConfig.MAX_PER;
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
        discoveredPatterns.add(sb.toString());
    }

    private List<int[]> deepCopySequence(List<int[]> orig) {
        List<int[]> copy = new ArrayList<>(orig.size());
        for (int[] arr : orig) copy.add(Arrays.copyOf(arr, arr.length));
        return copy;
    }

    private boolean containsTarget(List<int[]> prefix) {
        for (int[] itemset : prefix) {
            for (int item : itemset) {
                if (AppConfig.TARGET_ITEMS.contains(item)) return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        new BatchTaSPM().run();
    }
}