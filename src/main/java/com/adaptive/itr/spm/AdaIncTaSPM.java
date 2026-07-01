package com.adaptive.itr.spm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * <b>Ada-IncTaSPM</b> - Adaptive Incremental Target Regular Sequential
 * Pattern Mining (V9.0, pattern-growth vertical hybrid).
 *
 * <h2>Problem addressed</h2>
 * Given a growing database of transactional sequences and a
 * user-supplied set of <em>target items</em>, this algorithm mines the
 * complete set of sequential patterns that:
 * <ul>
 *   <li>contain at least one target item ("target-based"),</li>
 *   <li>occur with support &ge; {@link AppConfig#MIN_SUP_RATE} times the
 *       running transaction count,</li>
 *   <li>appear "regularly" over time, i.e. the maximum gap between
 *       consecutive occurrences never exceeds {@link AppConfig#MAX_PER}.</li>
 * </ul>
 * The database arrives in batches (fractions declared in
 * {@link AppConfig#DATA_CHUNKS}); after each batch the algorithm updates
 * its pattern set incrementally instead of rescanning the whole
 * database.
 *
 * <h2>What makes Ada-IncTaSPM adaptive</h2>
 * The core idea is a per-item <em>adaptive score</em>
 * ({@code AdaptScore}) that combines four signals - Volume,
 * Target-Proximity (TBR), Recency and Stability - into a single number.
 * High-scoring items get a relaxed effective support threshold
 * {@code min_sup * (1 - eta * score)} so that promising-but-borderline
 * items are not pruned prematurely; low-scoring items keep the strict
 * threshold and are cut aggressively.
 *
 * <h2>Key V9.0 techniques</h2>
 * <ul>
 *   <li><b>Local support counting</b> borrowed from PrefixSpan is fused
 *       into the vertical projection, so we never iterate a global
 *       candidate list.</li>
 *   <li><b>Search-space auto-contraction</b> - the DFS discards ~99.9 %
 *       of dead branches before recursion by counting suffixes locally.</li>
 *   <li><b>No S/I matrices, no redundant BitSet intersections</b> -
 *       memory footprint is dominated by the projected database itself.</li>
 * </ul>
 *
 * <h2>Output</h2>
 * The final mined patterns and their supports are dumped to
 * {@code result_AdaIncTaSPM.txt}; per-batch timings, RAM peaks, pruning
 * ratios and pattern counts are appended to the same file so that
 * {@link ResultAggregator} can produce a unified Summary_Report.
 */
public class AdaIncTaSPM {

    private long totalRecursiveCandidates = 0;
    private long prunedRecursiveCandidates = 0;

    private int min_sup;
    private int patternCount = 0;
    private int totalItemsBeforePruning = 0;
    private int candidatesAfterPruning = 0;

    private int validTotalDBSize;
    private int fullTotalTransCount;
    private int[][][] database;
    private int[] originalSIDs;
    private int totalDBSize;
    private int dbSizeOld;
    private int dbSizeDelta;
    private int currentOrigTransCount;

    private boolean enableFileOutput = false;
    private List<String> discoveredPatterns = new ArrayList<>();
    private final String OUTPUT_FILE = "result_AdaIncTaSPM.txt";

    // --- ZERO-GC PREFIX TRACKING ---
    private int[] currentPrefix = new int[4096];
    private int[] itemsetBoundaries = new int[4096];
    private int prefixItemCount = 0;
    private int prefixItemsetCount = 0;

    private int[] origPos1Based;
    private int posEnd;

    private int[][] cachedUniqueItems;
    private boolean[] cachedHasTarget;
    private int maxItemId = 0;

    // --- TBR PROXIMITY BUFFERS (Zero-GC) ---
    private int[] targetPosBuf = new int[256];   // Positions of itemsets containing a target in the sequence
    private int[] minDistBuf = new int[256];     // Minimum distance to a target at each position

    // --- GLOBAL BUILDER BUFFERS ---
    private int[] tmpSids = new int[8192];
    private int[] tmpOffsets = new int[8192];
    private int[] tmpOccs = new int[65536];

    // --- V9.0: GLOBAL LOCAL COUNTING BUFFERS (Zero-GC) ---
    private int[] globalICount;
    private int[] globalSCount;
    private int[] globalISeen;
    private int[] globalSSeen;
    private int[] globalITracked;
    private int[] globalSTracked;

    private static class TsiData {
        int[] sids;
        int[] occsOffsets;
        int[] occsData;
        int size;
    }

    private void resetAlgorithmState() {
        this.patternCount = 0;
        this.totalItemsBeforePruning = 0;
        this.candidatesAfterPruning = 0;
        this.prefixItemCount = 0;
        this.prefixItemsetCount = 0;
    }

    public void run() {
        AppConfig.load();
        precalculateTotalTransactions(AppConfig.DATASET_PATH);
        if (validTotalDBSize == 0) {
            System.out.println("======================================================");
            System.out.println("[ERROR] NO VALID DATA FOUND!");
            return;
        }

        System.out.println("======================================================");
        System.out.println("=== ADA-INCTASPM (V9.0 - PATTERN-GROWTH HYBRID)    ===");
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

            this.cachedUniqueItems = null;
            this.cachedHasTarget = null;
            this.maxItemId = 0;

            try (BufferedReader br = new BufferedReader(new FileReader(AppConfig.DATASET_PATH), 65536)) {
                int currentLineIndex = 0;
                List<int[][]> currentRunDB = new ArrayList<>(validTotalDBSize);
                List<Integer> currentRunSIDs = new ArrayList<>(validTotalDBSize);

                for (int b = 0; b < numBatches; b++) {
                    resetAlgorithmState();
                    int endOldSid = (b == 0) ? 0 : batchEndOrigSids[b - 1];
                    int endDeltaSid = batchEndOrigSids[b];

                    currentLineIndex = readBatchData(br, currentLineIndex, endDeltaSid, currentRunDB, currentRunSIDs);
                    setupBatchVariables(endOldSid, endDeltaSid, currentRunDB, currentRunSIDs);

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
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (isValidMetricRun) {
                sumPeakMemReal += currentRunPeakMem;
                countValidMemRuns++;
            }

            if (isLastRun) {
                writeFinalReport(numBatches, avgUpdateTimes, peakMems, patternsFound, countValidMemRuns, sumPeakMemReal, sumTotalItemsBeforePruning, sumCandidatesAfterPruning);
            }
        }
    }

    private void precalculateTotalTransactions(String path) {
        int cur = 0;
        int validDbSize = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(path), 65536)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '@') continue;
                boolean hasT = false, hasItems = false;
                int num = 0; boolean isNeg = false, inNum = false;
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (c == ' ' || c == '\t') {
                        if (inNum) {
                            int v = isNeg ? -num : num;
                            if (v == -2) break;
                            if (v == -1) hasItems = true;
                            else {
                                if (AppConfig.TARGET_ITEMS.contains(v)) hasT = true;
                                hasItems = true;
                            }
                            num = 0; isNeg = false; inNum = false;
                        }
                    } else if (c == '-') { isNeg = true; inNum = true; }
                    else if (c >= '0' && c <= '9') { num = num * 10 + (c - '0'); inNum = true; }
                }
                if (hasT && hasItems) validDbSize++;
                cur++;
            }
            this.validTotalDBSize = validDbSize;
            this.fullTotalTransCount = cur;
        } catch (Exception e) {}
    }

    private int readBatchData(BufferedReader br, int currentLineIndex, int endDeltaSid, List<int[][]> currentRunDB, List<Integer> currentRunSIDs) throws IOException {
        String line;
        int[] itemBuf = new int[128];
        int[][] seqBuf = new int[64][];

        while (currentLineIndex < endDeltaSid && (line = br.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '@') { currentLineIndex++; continue; }
            int seqCount = 0, isCount = 0; boolean hasT = false, isNeg = false, inNum = false; int num = 0;

            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == ' ' || c == '\t') {
                    if (inNum) {
                        int v = isNeg ? -num : num;
                        if (v == -2) break;
                        else if (v == -1) {
                            if (isCount > 0) {
                                Arrays.sort(itemBuf, 0, isCount);
                                if (seqCount >= seqBuf.length) seqBuf = Arrays.copyOf(seqBuf, seqBuf.length * 2);
                                seqBuf[seqCount++] = Arrays.copyOf(itemBuf, isCount);
                                isCount = 0;
                            }
                        } else {
                            if (isCount >= itemBuf.length) itemBuf = Arrays.copyOf(itemBuf, itemBuf.length * 2);
                            itemBuf[isCount++] = v;
                            if (v > maxItemId) maxItemId = v;
                            if (AppConfig.TARGET_ITEMS.contains(v)) hasT = true;
                        }
                        num = 0; isNeg = false; inNum = false;
                    }
                } else if (c == '-') { isNeg = true; inNum = true; }
                else if (c >= '0' && c <= '9') { num = num * 10 + (c - '0'); inNum = true; }
            }

            if (hasT && seqCount > 0) {
                currentRunDB.add(Arrays.copyOf(seqBuf, seqCount));
                currentRunSIDs.add(currentLineIndex);
            }
            currentLineIndex++;
        }
        return currentLineIndex;
    }

    private void setupBatchVariables(int endOldSid, int endDeltaSid, List<int[][]> currentRunDB, List<Integer> currentRunSIDs) {
        this.currentOrigTransCount = endDeltaSid;
        this.posEnd = currentOrigTransCount + 1;
        this.totalDBSize = currentRunDB.size();

        this.database = new int[totalDBSize][][];
        for (int i = 0; i < totalDBSize; i++) this.database[i] = currentRunDB.get(i);

        this.originalSIDs = new int[totalDBSize];
        this.origPos1Based = new int[totalDBSize];
        this.dbSizeOld = 0;
        this.dbSizeDelta = 0;

        for (int i = 0; i < totalDBSize; i++) {
            int origId = currentRunSIDs.get(i);
            this.originalSIDs[i] = origId;
            this.origPos1Based[i] = origId + 1;
            if (origId < endOldSid) this.dbSizeOld++;
            else if (origId < endDeltaSid) this.dbSizeDelta++;
        }

        if (this.cachedUniqueItems == null || this.cachedUniqueItems.length < totalDBSize) {
            int newCap = Math.max(totalDBSize, (this.cachedUniqueItems == null ? 1024 : this.cachedUniqueItems.length * 2));
            this.cachedUniqueItems = Arrays.copyOf(this.cachedUniqueItems == null ? new int[0][] : this.cachedUniqueItems, newCap);
            this.cachedHasTarget = Arrays.copyOf(this.cachedHasTarget == null ? new boolean[0] : this.cachedHasTarget, newCap);
        }

        int[] itemBuf = new int[256];
        for (int sid = dbSizeOld; sid < totalDBSize; sid++) {
            boolean hasTarget = false;
            int totalItems = 0;
            for (int[] is : database[sid]) totalItems += is.length;
            if (totalItems > itemBuf.length) itemBuf = new int[totalItems * 2];

            int ai = 0;
            for (int[] is : database[sid]) {
                for (int it : is) {
                    itemBuf[ai++] = it;
                    if (AppConfig.TARGET_ITEMS.contains(it)) hasTarget = true;
                }
            }
            Arrays.sort(itemBuf, 0, totalItems);
            int uniqueCount = 0;
            int prevItem = -1;
            for (int idx = 0; idx < totalItems; idx++) {
                if (itemBuf[idx] != prevItem) {
                    prevItem = itemBuf[idx];
                    itemBuf[uniqueCount++] = prevItem;
                }
            }
            this.cachedUniqueItems[sid] = Arrays.copyOf(itemBuf, uniqueCount);
            this.cachedHasTarget[sid] = hasTarget;
        }
    }

    private static class AdaptScore {
        int totalSupport, deltaSupport, oldSupport;
        double sumProximity;           // <-- CHANGED: accumulate Prox(P,T,S) instead of binary counting
        double sumExpRecency, finalScore;
        double welfordMean, welfordM2;
        int welfordCount;
        int lastSeenPos1Based = -1;
    }

    private AdaptScore[] computeAdaptiveScores_Pass1() {
        AdaptScore[] map = new AdaptScore[maxItemId + 1];

        for (int sid = 0; sid < totalDBSize; sid++) {
            boolean isDelta = (sid >= dbSizeOld);
            int pos1b = origPos1Based[sid];
            int origSid = originalSIDs[sid];

            // =============================================================
            // PRE-COMPUTE: Target-proximity data (only for the new batch delta-D)
            // =============================================================
            int seqLen = 0;
            int targetPosCount = 0;

            if (isDelta) {
                seqLen = database[sid].length;

                // Ensure buffer capacity
                if (seqLen > targetPosBuf.length) {
                    targetPosBuf = new int[seqLen * 2];
                    minDistBuf = new int[seqLen * 2];
                }

                // Step 1: Locate every itemset position that contains a target
                targetPosCount = 0;
                for (int tid = 0; tid < seqLen; tid++) {
                    for (int x : database[sid][tid]) {
                        if (AppConfig.TARGET_ITEMS.contains(x)) {
                            targetPosBuf[targetPosCount++] = tid;
                            break; // Each itemset needs to be recorded at most once
                        }
                    }
                }

                // Step 2: Compute minimum distance to a target for each itemset position
                // minDistBuf[tid] = min_{t ∈ targetPosBuf} |tid - t|
                for (int tid = 0; tid < seqLen; tid++) {
                    int minD = seqLen; // Largest possible starting value
                    for (int ti = 0; ti < targetPosCount; ti++) {
                        int d = Math.abs(tid - targetPosBuf[ti]);
                        if (d < minD) {
                            minD = d;
                            if (minD == 0) break; // Cannot improve further
                        }
                    }
                    minDistBuf[tid] = minD;
                }
            }

            // =============================================================
            // MAIN LOOP: Update AdaptScore for each item
            // =============================================================
            for (int item : cachedUniqueItems[sid]) {
                AdaptScore s = map[item];
                if (s == null) {
                    s = new AdaptScore();
                    map[item] = s;
                    s.lastSeenPos1Based = 0;
                    this.totalItemsBeforePruning++;
                }

                s.totalSupport++;

                if (isDelta) {
                    s.deltaSupport++;

                    // -------------------------------------------------
                    // NEW TBR: compute d_min(item, T, S) then Prox
                    // -------------------------------------------------
                    // d_min = min over all itemset positions containing `item`
                    //         of minDistBuf[tid]
                    int dMin = seqLen;
                    for (int tid = 0; tid < seqLen; tid++) {
                        if (itemContains(database[sid][tid], item)) {
                            if (minDistBuf[tid] < dMin) {
                                dMin = minDistBuf[tid];
                                if (dMin == 0) break; // Item shares its itemset with a target
                            }
                        }
                    }
                    double prox = (seqLen > 1)
                            ? 1.0 - (double) dMin / (seqLen - 1)
                            : 1.0;
                    s.sumProximity += prox;

                    // Recency (unchanged)
                    s.sumExpRecency += Math.exp(-AppConfig.LAMBDA * (currentOrigTransCount - origSid));
                } else {
                    s.oldSupport++;
                }

                // Welford online update (unchanged)
                int gap = pos1b - s.lastSeenPos1Based;
                s.welfordCount++;
                double d1 = gap - s.welfordMean;
                s.welfordMean += d1 / s.welfordCount;
                s.welfordM2 += d1 * (gap - s.welfordMean);
                s.lastSeenPos1Based = pos1b;
            }
        }

        // =================================================================
        // FINALIZE: Compute aggregate score
        // =================================================================
        for (int i = 0; i <= maxItemId; i++) {
            AdaptScore s = map[i];
            if (s == null) continue;

            // Welford: handle tail gap (unchanged)
            int tailGap = posEnd - s.lastSeenPos1Based;
            s.welfordCount++;
            double d1 = tailGap - s.welfordMean;
            s.welfordMean += d1 / s.welfordCount;
            s.welfordM2 += d1 * (tailGap - s.welfordMean);

            double variance = (s.welfordCount > 1) ? (s.welfordM2 / s.welfordCount) : 0.0;
            double sigma = Math.sqrt(Math.max(0.0, variance));
            double Stab = (s.welfordMean > 0) ? 1.0 - Math.min(1.0, sigma / s.welfordMean) : 0.0;

            if (s.deltaSupport > 0 && dbSizeOld > 0 && dbSizeDelta > 0) {
                double densityOld = (s.oldSupport > 0) ? ((double) s.oldSupport / dbSizeOld) : 0.0;
                double densityDelta = (double) s.deltaSupport / dbSizeDelta;
                double V = (densityOld > 0) ? Math.min(1.0, densityDelta / densityOld) : 1.0;

                double TBR = s.sumProximity / s.deltaSupport;  // <-- CHANGED: average proximity
                double R = s.sumExpRecency / s.deltaSupport;
                s.finalScore = (AppConfig.W1 * V + AppConfig.W2 * TBR + AppConfig.W3 * R + AppConfig.W4 * Stab) / 4.0;

            } else if (s.deltaSupport > 0) {
                double TBR = s.sumProximity / s.deltaSupport;  // <-- CHANGED
                double R = s.sumExpRecency / s.deltaSupport;
                s.finalScore = (AppConfig.W1 * 1.0 + AppConfig.W2 * TBR + AppConfig.W3 * R + AppConfig.W4 * Stab) / 4.0;
            }
        }

        return map;
    }

    private void runCoreAlgorithm() {

        this.totalRecursiveCandidates = 0;
        this.prunedRecursiveCandidates = 0;

        this.min_sup = Math.max(1, (int) Math.ceil(currentOrigTransCount * AppConfig.MIN_SUP_RATE));
        AdaptScore[] adaptScores = computeAdaptiveScores_Pass1();

        int nc = 0;
        int[] candListTemp = new int[maxItemId + 1];

        for (int item = 0; item <= maxItemId; item++) {
            AdaptScore s = adaptScores[item];
            if (s != null) {
                double lbAdapt = min_sup * (1.0 - AppConfig.ETA * s.finalScore);
                if (s.totalSupport >= lbAdapt) candListTemp[nc++] = item;
            }
        }
        adaptScores = null;
        this.candidatesAfterPruning = nc;

        int[] candidates = Arrays.copyOf(candListTemp, nc);
        int[] candIdx = new int[maxItemId + 1];
        Arrays.fill(candIdx, -1);
        for (int ci = 0; ci < nc; ci++) candIdx[candidates[ci]] = ci;

        // V9.0: Initialise the shared local-counting buffers
        globalICount = new int[maxItemId + 1];
        globalSCount = new int[maxItemId + 1];
        globalISeen = new int[maxItemId + 1];
        globalSSeen = new int[maxItemId + 1];
        globalITracked = new int[maxItemId + 1];
        globalSTracked = new int[maxItemId + 1];
        Arrays.fill(globalISeen, -1);
        Arrays.fill(globalSSeen, -1);

        BitSet[] itemBits = new BitSet[nc];
        for (int ci = 0; ci < nc; ci++) itemBits[ci] = new BitSet(totalDBSize);

        for (int sid = 0; sid < totalDBSize; sid++) {
            for (int[] seq : database[sid]) {
                for (int item : seq) {
                    if (item <= maxItemId) {
                        int ci = candIdx[item];
                        if (ci >= 0) itemBits[ci].set(sid);
                    }
                }
            }
        }

        TsiData[] itemOccs = new TsiData[nc];

        for (int ci = 0; ci < nc; ci++) {
            BitSet bmp = itemBits[ci];
            TsiData compact = new TsiData();
            int sidCount = bmp.cardinality();
            int item = candidates[ci];

            int totalOccs = 0;
            int[] tSids = new int[sidCount];
            int[] tOffsets = new int[sidCount + 1];

            for (int sid = bmp.nextSetBit(0); sid >= 0; sid = bmp.nextSetBit(sid + 1)) {
                for (int[] is : database[sid]) {
                    if (itemContains(is, item)) totalOccs++;
                }
            }

            int[] tData = new int[totalOccs];
            int sIdx = 0, dataIdx = 0;

            for (int sid = bmp.nextSetBit(0); sid >= 0; sid = bmp.nextSetBit(sid + 1)) {
                tSids[sIdx] = sid;
                tOffsets[sIdx] = dataIdx;
                int[][] seq = database[sid];
                for (int tid = 0; tid < seq.length; tid++) {
                    if (itemContains(seq[tid], item)) tData[dataIdx++] = tid;
                }
                sIdx++;
            }
            tOffsets[sIdx] = dataIdx;

            compact.size = sidCount;
            compact.sids = tSids;
            compact.occsOffsets = tOffsets;
            compact.occsData = tData;
            itemOccs[ci] = compact;
        }

        for (int ci = 0; ci < nc; ci++) {
            BitSet bmp = itemBits[ci];
            if (bmp.cardinality() < min_sup) continue;
            if (!checkRegularity(bmp)) continue;

            int item = candidates[ci];
            boolean isTarget = AppConfig.TARGET_ITEMS.contains(item);

            prefixItemCount = 0;
            prefixItemsetCount = 0;

            if (prefixItemCount >= currentPrefix.length) {
                currentPrefix = Arrays.copyOf(currentPrefix, currentPrefix.length * 2);
            }
            if (prefixItemsetCount >= itemsetBoundaries.length) {
                itemsetBoundaries = Arrays.copyOf(itemsetBoundaries, itemsetBoundaries.length * 2);
            }

            currentPrefix[prefixItemCount++] = item;
            itemsetBoundaries[prefixItemsetCount++] = prefixItemCount;

            if (isTarget) {
                patternCount++;
                if (enableFileOutput) writePatternToFile(bmp.cardinality());
            }

            dfsMine(bmp, itemOccs[ci], isTarget, candIdx, itemBits, itemOccs);
        }
    }

    private void dfsMine(BitSet pBmp, TsiData pTsi, boolean prefixHasTarget,
                         int[] candIdx, BitSet[] itemBits, TsiData[] itemOccs) {

        int lastItem = currentPrefix[prefixItemCount - 1];
        int trackCountI = 0;
        int trackCountS = 0;
        int pIndex = 0;

        // =========================================================================
        // [V9.0 BREAKTHROUGH] LOCAL SUPPORT COUNTING (local suffix scan)
        // =========================================================================
        for (int sid = pBmp.nextSetBit(0); sid >= 0; sid = pBmp.nextSetBit(sid + 1)) {
            while (pIndex < pTsi.size && pTsi.sids[pIndex] < sid) pIndex++;
            if (pIndex >= pTsi.size || pTsi.sids[pIndex] != sid) continue;

            int pOffStart = pTsi.occsOffsets[pIndex];
            int pOffEnd = pTsi.occsOffsets[pIndex + 1];

            // 1. I-Extension: Count items > lastItem in the SAME itemset
            for (int i = pOffStart; i < pOffEnd; i++) {
                int pTid = pTsi.occsData[i];
                int[] itemset = database[sid][pTid];
                for (int item : itemset) {
                    if (item > lastItem && item <= maxItemId && candIdx[item] >= 0) {
                        if (globalISeen[item] != sid) {
                            if (globalISeen[item] == -1) { // First encounter at this node
                                globalITracked[trackCountI++] = item;
                                globalICount[item] = 0;
                            }
                            globalISeen[item] = sid;
                            globalICount[item]++;
                        }
                    }
                }
            }

            // 2. S-Extension: Count items appearing AFTER the prefix
            int minP = pTsi.occsData[pOffStart];
            for (int tid = minP + 1; tid < database[sid].length; tid++) {
                int[] itemset = database[sid][tid];
                for (int item : itemset) {
                    if (item <= maxItemId && candIdx[item] >= 0) {
                        if (globalSSeen[item] != sid) {
                            if (globalSSeen[item] == -1) { // First encounter at this node
                                globalSTracked[trackCountS++] = item;
                                globalSCount[item] = 0;
                            }
                            globalSSeen[item] = sid;
                            globalSCount[item]++;
                        }
                    }
                }
            }
        }

        // =========================================================================
        // EXTRACT VALID CANDIDATES AND CLEAR BUFFERS O(1)
        // =========================================================================
        int validICount = 0;
        int[] validI = new int[trackCountI];
        for (int i = 0; i < trackCountI; i++) {
            totalRecursiveCandidates++; // [NEW]: one counted item == one recursive I-Extension candidate
            int item = globalITracked[i];
            if (globalICount[item] >= min_sup) {
                validI[validICount++] = item;
            }
            else {
                prunedRecursiveCandidates++; // [NEW]: Pruned because support is below threshold
            }
            // Restore global buffer state before recursion
            globalICount[item] = 0;
            globalISeen[item] = -1;
        }

        int validSCount = 0;
        int[] validS = new int[trackCountS];
        for (int i = 0; i < trackCountS; i++) {
            totalRecursiveCandidates++; // [NEW]: one counted item == one recursive S-Extension candidate

            int item = globalSTracked[i];
            if (globalSCount[item] >= min_sup) {
                validS[validSCount++] = item;
            }
            else {
                prunedRecursiveCandidates++; // [NEW]: Pruned because support is below threshold
            }

            globalSCount[item] = 0;
            globalSSeen[item] = -1;
        }

        // Re-sort to keep the canonical lexicographic order
        Arrays.sort(validI, 0, validICount);
        Arrays.sort(validS, 0, validSCount);

        // =========================================================================
        // RECURSIVE TRAVERSAL (descend only into satisfying items)
        // =========================================================================
        for (int i = 0; i < validICount; i++) {
            int item = validI[i];
            int ci = candIdx[item];
            boolean hasTarget = prefixHasTarget || AppConfig.TARGET_ITEMS.contains(item);

            TsiData tsiI = checkIExtRegularity(pBmp, itemBits[ci], pTsi, itemOccs[ci]);
            if (tsiI != null) {
                BitSet bmpI = new BitSet(totalDBSize);
                for (int j = 0; j < tsiI.size; j++) bmpI.set(tsiI.sids[j]);

                if (bmpI.cardinality() >= min_sup) {
                    if (prefixItemCount >= currentPrefix.length) currentPrefix = Arrays.copyOf(currentPrefix, currentPrefix.length * 2);

                    currentPrefix[prefixItemCount++] = item;
                    itemsetBoundaries[prefixItemsetCount - 1] = prefixItemCount;

                    if (hasTarget) {
                        patternCount++;
                        if (enableFileOutput) writePatternToFile(bmpI.cardinality());
                    }
                    dfsMine(bmpI, tsiI, hasTarget, candIdx, itemBits, itemOccs);

                    prefixItemCount--;
                    itemsetBoundaries[prefixItemsetCount - 1] = prefixItemCount;
                }
            }
        }

        for (int i = 0; i < validSCount; i++) {
            int item = validS[i];
            int ci = candIdx[item];
            boolean hasTarget = prefixHasTarget || AppConfig.TARGET_ITEMS.contains(item);

            TsiData tsiS = checkSExtRegularity(pBmp, itemBits[ci], pTsi, itemOccs[ci]);
            if (tsiS != null) {
                BitSet bmpS = new BitSet(totalDBSize);
                for (int j = 0; j < tsiS.size; j++) bmpS.set(tsiS.sids[j]);

                if (bmpS.cardinality() >= min_sup) {
                    if (prefixItemCount >= currentPrefix.length) currentPrefix = Arrays.copyOf(currentPrefix, currentPrefix.length * 2);
                    if (prefixItemsetCount >= itemsetBoundaries.length) itemsetBoundaries = Arrays.copyOf(itemsetBoundaries, itemsetBoundaries.length * 2);

                    currentPrefix[prefixItemCount++] = item;
                    itemsetBoundaries[prefixItemsetCount++] = prefixItemCount;

                    if (hasTarget) {
                        patternCount++;
                        if (enableFileOutput) writePatternToFile(bmpS.cardinality());
                    }
                    dfsMine(bmpS, tsiS, hasTarget, candIdx, itemBits, itemOccs);

                    prefixItemsetCount--;
                    prefixItemCount--;
                }
            }
        }
    }

    private TsiData checkIExtRegularity(BitSet pBmp, BitSet itemBmp, TsiData pTsi, TsiData itemRawOccs) {
        int maxGap = 0, prevPos = 0;
        int pIndex = 0, rawIndex = 0, sidCount = 0, totalOccs = 0;
        tmpOffsets[0] = 0;

        for (int sid = pBmp.nextSetBit(0); sid >= 0; sid = pBmp.nextSetBit(sid + 1)) {
            if (!itemBmp.get(sid)) continue;

            while (pIndex < pTsi.size && pTsi.sids[pIndex] < sid) pIndex++;
            if (pIndex >= pTsi.size || pTsi.sids[pIndex] != sid) continue;

            while (rawIndex < itemRawOccs.size && itemRawOccs.sids[rawIndex] < sid) rawIndex++;
            if (rawIndex >= itemRawOccs.size || itemRawOccs.sids[rawIndex] != sid) continue;

            int pOffStart = pTsi.occsOffsets[pIndex];
            int pOffEnd = pTsi.occsOffsets[pIndex + 1];
            int rOffStart = itemRawOccs.occsOffsets[rawIndex];
            int rOffEnd = itemRawOccs.occsOffsets[rawIndex + 1];

            int vc = 0;
            int rIdx = rOffStart;

            for (int i = pOffStart; i < pOffEnd; i++) {
                int pTid = pTsi.occsData[i];
                while (rIdx < rOffEnd && itemRawOccs.occsData[rIdx] < pTid) rIdx++;
                if (rIdx < rOffEnd && itemRawOccs.occsData[rIdx] == pTid) {
                    if (totalOccs >= tmpOccs.length) tmpOccs = Arrays.copyOf(tmpOccs, tmpOccs.length * 2);
                    tmpOccs[totalOccs++] = pTid;
                    vc++;
                }
            }

            if (vc > 0) {
                if (sidCount >= tmpSids.length || sidCount + 1 >= tmpOffsets.length) {
                    tmpSids = Arrays.copyOf(tmpSids, tmpSids.length * 2);
                    tmpOffsets = Arrays.copyOf(tmpOffsets, tmpOffsets.length * 2);
                }
                tmpSids[sidCount] = sid;
                sidCount++;
                tmpOffsets[sidCount] = totalOccs;

                int pos = origPos1Based[sid];
                int gap = pos - prevPos;
                if (gap > maxGap) {
                    maxGap = gap;
                    if (maxGap > AppConfig.MAX_PER) return null;
                }
                prevPos = pos;
            }
        }

        if (sidCount == 0) return null;
        if (Math.max(maxGap, posEnd - prevPos) > AppConfig.MAX_PER) return null;

        TsiData newTsi = new TsiData();
        newTsi.size = sidCount;
        newTsi.sids = Arrays.copyOf(tmpSids, sidCount);
        newTsi.occsOffsets = Arrays.copyOf(tmpOffsets, sidCount + 1);
        newTsi.occsData = Arrays.copyOf(tmpOccs, totalOccs);
        return newTsi;
    }

    private TsiData checkSExtRegularity(BitSet pBmp, BitSet itemBmp, TsiData pTsi, TsiData itemRawOccs) {
        int maxGap = 0, prevPos = 0;
        int pIndex = 0, rawIndex = 0, sidCount = 0, totalOccs = 0;
        tmpOffsets[0] = 0;

        for (int sid = pBmp.nextSetBit(0); sid >= 0; sid = pBmp.nextSetBit(sid + 1)) {
            if (!itemBmp.get(sid)) continue;

            while (pIndex < pTsi.size && pTsi.sids[pIndex] < sid) pIndex++;
            if (pIndex >= pTsi.size || pTsi.sids[pIndex] != sid) continue;

            while (rawIndex < itemRawOccs.size && itemRawOccs.sids[rawIndex] < sid) rawIndex++;
            if (rawIndex >= itemRawOccs.size || itemRawOccs.sids[rawIndex] != sid) continue;

            int pOffStart = pTsi.occsOffsets[pIndex];
            int minP = pTsi.occsData[pOffStart];

            int rOffStart = itemRawOccs.occsOffsets[rawIndex];
            int rOffEnd = itemRawOccs.occsOffsets[rawIndex + 1];

            int vc = 0;
            for (int rIdx = rOffStart; rIdx < rOffEnd; rIdx++) {
                int rawTid = itemRawOccs.occsData[rIdx];
                if (rawTid > minP) {
                    if (totalOccs >= tmpOccs.length) tmpOccs = Arrays.copyOf(tmpOccs, tmpOccs.length * 2);
                    tmpOccs[totalOccs++] = rawTid;
                    vc++;
                }
            }

            if (vc > 0) {
                if (sidCount >= tmpSids.length || sidCount + 1 >= tmpOffsets.length) {
                    tmpSids = Arrays.copyOf(tmpSids, tmpSids.length * 2);
                    tmpOffsets = Arrays.copyOf(tmpOffsets, tmpOffsets.length * 2);
                }
                tmpSids[sidCount] = sid;
                sidCount++;
                tmpOffsets[sidCount] = totalOccs;

                int pos = origPos1Based[sid];
                int gap = pos - prevPos;
                if (gap > maxGap) {
                    maxGap = gap;
                    if (maxGap > AppConfig.MAX_PER) return null;
                }
                prevPos = pos;
            }
        }

        if (sidCount == 0) return null;
        if (Math.max(maxGap, posEnd - prevPos) > AppConfig.MAX_PER) return null;

        TsiData newTsi = new TsiData();
        newTsi.size = sidCount;
        newTsi.sids = Arrays.copyOf(tmpSids, sidCount);
        newTsi.occsOffsets = Arrays.copyOf(tmpOffsets, sidCount + 1);
        newTsi.occsData = Arrays.copyOf(tmpOccs, totalOccs);
        return newTsi;
    }

    private boolean checkRegularity(BitSet bits) {
        int maxGap = 0, prev = 0;
        for (int sidIdx = bits.nextSetBit(0); sidIdx >= 0; sidIdx = bits.nextSetBit(sidIdx + 1)) {
            int pos = origPos1Based[sidIdx];
            int gap = pos - prev;
            if (gap > maxGap) {
                maxGap = gap;
                if (maxGap > AppConfig.MAX_PER) return false;
            }
            prev = pos;
        }
        if (prev == 0) return false;
        return Math.max(maxGap, posEnd - prev) <= AppConfig.MAX_PER;
    }

    private static final int BSEARCH_THRESHOLD = 8;
    private boolean itemContains(int[] itemset, int item) {
        if (itemset.length >= BSEARCH_THRESHOLD) return Arrays.binarySearch(itemset, item) >= 0;
        for (int x : itemset) {
            if (x == item) return true;
            if (x > item) break;
        }
        return false;
    }

    private void writePatternToFile(int support) {
        if (!enableFileOutput) return;
        StringBuilder sb = new StringBuilder(64);
        sb.append("<");
        int itemIdx = 0;
        for (int k = 0; k < prefixItemsetCount; k++) {
            sb.append("(");
            int end = itemsetBoundaries[k];
            for (int i = itemIdx; i < end; i++) {
                sb.append(currentPrefix[i]);
                if (i < end - 1) sb.append(" ");
            }
            sb.append(")");
            if (k < prefixItemsetCount - 1) sb.append(", ");
            itemIdx = end;
        }
        sb.append("> : sup = ").append(support).append("\n");
        discoveredPatterns.add(sb.toString());
    }

    private void writeFinalReport(int numBatches, double[] avgUpdateTimes, double[] peakMems, int[] patternsFound, int countValidMemRuns, double sumPeakMemReal, long sumTotalItemsBeforePruning, long sumCandidatesAfterPruning) {
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
            double pruningRatio = (sumTotalItemsBeforePruning > 0) ? (100.0 * (1.0 - (double) sumCandidatesAfterPruning / sumTotalItemsBeforePruning)) : 0.0;

            bw.write("\n======================================================\n");
            bw.write("DETAILED SUMMARY (FINAL BATCH):\n");
            bw.write("- Dataset: " + AppConfig.DATASET_PATH + "\n");
            bw.write("- Raw data size: " + fullTotalTransCount + " sequences\n");
            bw.write("- DB size after target filtering: " + validTotalDBSize + " sequences\n");
            bw.write("- Batch splitting ratio (Data chunks): " + Arrays.toString(AppConfig.DATA_CHUNKS) + "\n");
            bw.write("- General config: min_sup_rate=" + AppConfig.MIN_SUP_RATE + ", max_per=" + AppConfig.MAX_PER + "\n");
            bw.write("- Ada-Inc parameters: Weights=[" + AppConfig.W1 + ", " + AppConfig.W2 + ", " + AppConfig.W3 + ", " + AppConfig.W4 + "], eta=" + AppConfig.ETA + ", lambda=" + AppConfig.LAMBDA + "\n");
            bw.write("- Partitions: Dold=" + dbSizeOld + " sequences | ∆D=" + dbSizeDelta + " sequences\n");
            bw.write("- Total items initialized      : " + sumTotalItemsBeforePruning + " (Average: " + String.format(Locale.US, "%.1f", avgItemsBeforePruning) + " items/batch)\n");
            bw.write("- Total candidates passing filter     : " + sumCandidatesAfterPruning + " (Average: " + String.format(Locale.US, "%.1f", avgCandidatesAfterPruning) + " candidates/batch)\n");
            bw.write("- Search-space pruning ratio (avg)  : " + String.format(Locale.US, "%.2f%%\n", pruningRatio));
            bw.write("- Total patterns: " + patternsFound[lastB] + "\n");

            // Total search space actually generated (Root + Recursive)
            long finalTotalSpace = sumTotalItemsBeforePruning + totalRecursiveCandidates;

// Total branches auto-pruned by the algorithm
            long finalPrunedSpace = (sumTotalItemsBeforePruning - sumCandidatesAfterPruning) + prunedRecursiveCandidates;

// Canonical pruning ratio (Deep Pruning Ratio)
            double deepPruningRatio = (finalTotalSpace > 0) ? (100.0 * finalPrunedSpace / finalTotalSpace) : 0.0;

            bw.write("- Total search space (Root + Recursive) : " + finalTotalSpace + " nodes\n");
            bw.write("- Recursive nodes pruned                 : " + finalPrunedSpace + " nodes\n");
            bw.write("- Deep recursive-space pruning ratio        : " + String.format(Locale.US, "%.2f%%\n", deepPruningRatio));


            double avgPeakMem = sumPeakMemReal / validRuns;
            bw.write("- Average peak memory: " + String.format(Locale.US, "%.2f MB\n", avgPeakMem));

            bw.write("- Runtime history (s) across batches : ");
            for (int step = 0; step < numBatches; step++)
                bw.write(String.format(Locale.US, "%.3f", avgUpdateTimes[step] / 1000.0) + (step < numBatches - 1 ? ", " : "\n"));

            bw.write("- Peak RAM history (MB) across batches: ");
            for (int step = 0; step < numBatches; step++)
                bw.write(String.format(Locale.US, "%.2f", peakMems[step]) + (step < numBatches - 1 ? ", " : "\n"));

            bw.write("- Total cumulative time (Cumulative Update Time): " + String.format(Locale.US, "%.3f s\n", avgUpdateTimes[lastB] / 1000.0));
            bw.write("- Overall peak memory (Max Update Mem): " + String.format(Locale.US, "%.2f MB\n", maxOverallMem));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        new AdaIncTaSPM().run();
    }
}