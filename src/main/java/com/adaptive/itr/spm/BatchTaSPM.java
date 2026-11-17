package com.adaptive.itr.spm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * <b>BatchTaSPM</b> - Static batch baseline for target-based
 * sequential pattern mining.
 *
 * <p>Pattern growth over projected databases. A projected sequence keeps every itemset where an
 * occurrence of the prefix ends, so I-extensions are searched in every itemset that holds the
 * prefix's last itemset, not only in the first one; the output is the complete set of regular
 * patterns that contain a target item.
 *
 * <p>Runs the whole pipeline from scratch on every batch: it keeps only the sequences that contain
 * a target item, rebuilds the projected database and mines it, with no state carried between
 * batches. The class was written for this benchmark; it is not the implementation of the TaSPM
 * authors and has no pruning strategy of TaSPM other than the filter on target sequences.
 *
 * <p>Entry points:
 * <ul>
 *   <li>{@link #mineSnapshot} - used by {@link StreamBench} for every measured run: the file is
 *       read by the caller outside the clock, so a batch's time covers building the filtered
 *       database and mining it.</li>
 *   <li>{@link #run} - the legacy harness: re-reads the dataset file from its start for every
 *       batch, so its wall time also includes reading the file.</li>
 * </ul>
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

    /**
     * One sequence of a projected database: every itemset where an occurrence of the prefix
     * ends. tids holds the itemset indices in increasing order and offs[k] the index, inside
     * itemset tids[k], of the last item of the prefix. S-extensions grow from the first end
     * (tids[0]); I-extensions grow from every end, so an I-extension that occurs only after a
     * later occurrence of the prefix's last itemset is found.
     */
    private static class ProjectedSequence {
        final int sidIdx;
        final int[] tids, offs;
        ProjectedSequence(int sidIdx, int[] tids, int[] offs) {
            this.sidIdx = sidIdx;
            this.tids = tids;
            this.offs = offs;
        }
    }

    /** Growable pair of int lists used while one projection is being built. */
    private static final class EndList {
        final int sidIdx;
        int[] t = new int[4], o = new int[4];
        int n = 0;
        EndList(int sidIdx) { this.sidIdx = sidIdx; }
        void add(int tid, int off) {
            if (n == t.length) { t = Arrays.copyOf(t, 2 * n); o = Arrays.copyOf(o, 2 * n); }
            t[n] = tid; o[n] = off; n++;
        }
        ProjectedSequence done() { return new ProjectedSequence(sidIdx, Arrays.copyOf(t, n), Arrays.copyOf(o, n)); }
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

    // --- In-memory stream interface (StreamBench) ---
    /** When set, every output pattern is put here in canonical form "a b|c" with its support. */
    Map<String, Integer> sink = null;

    /**
     * Mines the whole current database from scratch, as the batch baseline does after every
     * batch; nothing is kept from an earlier call. The file is read by the caller (outside the
     * clock), so this covers building the filtered database and mining it.
     *
     * @param db      every target sequence received so far, in file order
     * @param lineIdx 0-based file line of each entry of db
     * @param n       sequences received so far (every non-empty line)
     * @return number of output patterns
     */
    public long mineSnapshot(List<int[][]> db, List<Integer> lineIdx, int n) {
        resetMiningState();
        List<RawSequence> raw = new ArrayList<>(db.size());
        for (int i = 0; i < db.size(); i++) {
            RawSequence r = new RawSequence(lineIdx.get(i));
            r.itemsets = Arrays.asList(db.get(i));
            raw.add(r);
        }
        this.enableFileOutput = sink != null;
        mineOnDataset(raw, n);
        this.enableFileOutput = false;
        return patternCount;
    }

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

        int[] batchEndOrigSids = AppConfig.batchEnds(fullTotalTransCount);
        int numBatches = batchEndOrigSids.length;

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

        this.min_sup = AppConfig.minSup(currentOrigTransCount);

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

        for (int item : frequentItems) {
            List<int[]> prefix = new ArrayList<>();
            prefix.add(new int[]{item});
            List<ProjectedSequence> nextPDB = projectItem(item);
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
            for (int i = pseq.tids[0] + 1; i < seq.length; i++) {
                for (int j = 0; j < seq[i].length; j++) {
                    int item = seq[i][j];
                    if (!seenInSidS.contains(item)) {
                        seenInSidS.add(item);
                        sCount.put(item, sCount.getOrDefault(item, 0) + 1);
                    }
                }
            }
            Set<Integer> seenInSidI = new HashSet<>();
            for (int k = 0; k < pseq.tids.length; k++) {
                int[] itemset = seq[pseq.tids[k]];
                for (int j = pseq.offs[k] + 1; j < itemset.length; j++) {
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
            Map<Integer, EndList> sEnds = new HashMap<>();
            for (int i = pseq.tids[0] + 1; i < seq.length; i++) {
                for (int j = 0; j < seq[i].length; j++) {
                    int item = seq[i][j];
                    if (validSItems.contains(item)) {
                        EndList el = sEnds.get(item);
                        if (el == null) { el = new EndList(pseq.sidIdx); sEnds.put(item, el); }
                        el.add(i, j);
                    }
                }
            }
            for (Map.Entry<Integer, EndList> en : sEnds.entrySet())
                sMap.computeIfAbsent(en.getKey(), k -> new ArrayList<>()).add(en.getValue().done());
            Map<Integer, EndList> iEnds = new HashMap<>();
            for (int k = 0; k < pseq.tids.length; k++) {
                int[] itemset = seq[pseq.tids[k]];
                for (int j = pseq.offs[k] + 1; j < itemset.length; j++) {
                    int item = itemset[j];
                    if (item > lastItemInPrefix && validIItems.contains(item)) {
                        EndList el = iEnds.get(item);
                        if (el == null) { el = new EndList(pseq.sidIdx); iEnds.put(item, el); }
                        el.add(pseq.tids[k], j);
                    }
                }
            }
            for (Map.Entry<Integer, EndList> en : iEnds.entrySet())
                iMap.computeIfAbsent(en.getKey(), k -> new ArrayList<>()).add(en.getValue().done());
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

    /** Projection of the one-item pattern (item): every itemset of every sequence that holds it. */
    private List<ProjectedSequence> projectItem(int item) {
        List<ProjectedSequence> nextPDB = new ArrayList<>();
        for (int sid = 0; sid < totalDBSize; sid++) {
            int[][] seq = database[sid];
            EndList el = null;
            for (int i = 0; i < seq.length; i++) {
                for (int j = 0; j < seq[i].length; j++) {
                    if (seq[i][j] == item) {
                        if (el == null) el = new EndList(sid);
                        el.add(i, j);
                        break;
                    }
                }
            }
            if (el != null) nextPDB.add(el.done());
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
        if (sink != null) {
            StringBuilder k = new StringBuilder(32);
            for (int q = 0; q < prefix.size(); q++) {
                if (q > 0) k.append('|');
                int[] is = prefix.get(q);
                for (int i = 0; i < is.length; i++) { if (i > 0) k.append(' '); k.append(is[i]); }
            }
            sink.put(k.toString(), support);
            return;
        }
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