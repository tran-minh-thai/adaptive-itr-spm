// SPDX-License-Identifier: MIT
package com.adaptive.itr.spm;

import java.math.BigDecimal;
import java.util.*;

/**
 * Checks PreLargeSeqTaSPM against the definition on random small streams, after every batch.
 * The reference is the exhaustive enumeration of {@link BatchRandomCheck#reference} on the whole
 * current database.
 *
 * Streams: an initial database of 4 to 10 sequences, then 2 to 8 batches of 1 to 5 sequences;
 * sequences of 1 to 4 itemsets of 1 to 3 distinct items over {1..5}; target set {1};
 * min_sup_rate in {0.2, 0.3, 0.5}; max_per in [2, N]; Sl / Su from the third argument.
 *
 * Usage: java -cp <classes> com.adaptive.itr.spm.PreLargeRandomCheck [cases] [seed] [lower]
 * Prints the number of snapshots compared, safe updates, rescans and differing snapshots.
 * Exit code 1 when any snapshot differs from the reference.
 */
public class PreLargeRandomCheck {

    static int[][] randomSequence(Random rnd) {
        int len = 1 + rnd.nextInt(4);
        int[][] seq = new int[len][];
        for (int i = 0; i < len; i++) {
            TreeSet<Integer> is = new TreeSet<>();
            int k = 1 + rnd.nextInt(3);
            while (is.size() < k) is.add(1 + rnd.nextInt(5));
            seq[i] = new int[is.size()];
            int u = 0;
            for (int x : is) seq[i][u++] = x;
        }
        return seq;
    }

    public static void main(String[] a) {
        int cases = a.length > 0 ? Integer.parseInt(a[0]) : 2000;
        long seed = a.length > 1 ? Long.parseLong(a[1]) : 20261117L;
        double lower = a.length > 2 ? Double.parseDouble(a[2]) : 0.5;
        Random rnd = new Random(seed);
        Set<Integer> T = new HashSet<>();
        T.add(1);
        String[] rates = {"0.2", "0.3", "0.5"};
        long snapshots = 0, nonEmpty = 0, refPatterns = 0, safeUpdates = 0, rescans = 0, badSnapshots = 0;
        int badCases = 0, shown = 0;
        for (int c = 0; c < cases; c++) {
            int init = 4 + rnd.nextInt(7);
            int nb = 2 + rnd.nextInt(7);
            List<List<int[][]>> stream = new ArrayList<>();
            int total = 0;
            for (int b = 0; b <= nb; b++) {
                int size = b == 0 ? init : 1 + rnd.nextInt(5);
                List<int[][]> part = new ArrayList<>();
                for (int s = 0; s < size; s++) part.add(randomSequence(rnd));
                stream.add(part);
                total += size;
            }
            String rate = rates[rnd.nextInt(rates.length)];
            int maxPer = 2 + rnd.nextInt(total - 1);
            BigDecimal r = new BigDecimal(rate);
            PreLargeSeqTaSPM pl = new PreLargeSeqTaSPM(r, lower, maxPer, T);
            pl.sink = new TreeMap<>();
            List<int[][]> all = new ArrayList<>();
            List<Integer> lines = new ArrayList<>();
            int n = 0;
            boolean caseBad = false;
            for (int b = 0; b <= nb; b++) {
                int from = all.size();
                for (int[][] seq : stream.get(b)) {
                    boolean t = false;
                    for (int[] is : seq) for (int x : is) if (T.contains(x)) t = true;
                    if (t) { all.add(seq); lines.add(n); }
                    n++;
                }
                long rescansBefore = pl.rescans;
                long cnt = pl.addBatch(all, lines, from, n);
                if (b > 0) { if (pl.rescans == rescansBefore) safeUpdates++; else rescans++; }
                int minSup = Math.max(1, r.multiply(BigDecimal.valueOf(n)).setScale(0, java.math.RoundingMode.CEILING).intValueExact());
                Map<String, Integer> ref = BatchRandomCheck.reference(all, lines, n, minSup, maxPer, T);
                snapshots++;
                if (!ref.isEmpty()) nonEmpty++;
                refPatterns += ref.size();
                Map<String, Integer> got = new TreeMap<>(pl.sink);
                if (!got.equals(ref) || cnt != ref.size()) {
                    badSnapshots++;
                    caseBad = true;
                    if (shown++ < 3) {
                        Set<String> miss = new TreeSet<>(ref.keySet()); miss.removeAll(got.keySet());
                        Set<String> extra = new TreeSet<>(got.keySet()); extra.removeAll(ref.keySet());
                        System.out.println("case " + c + " batch " + b + ": missing " + miss + " extra " + extra + " count=" + cnt + " ref=" + ref.size());
                    }
                }
            }
            if (caseBad) badCases++;
        }
        System.out.println("cases=" + cases + " seed=" + seed + " lower=" + lower + " snapshots=" + snapshots
                + " snapshots_with_nonempty_reference=" + nonEmpty + " reference_patterns=" + refPatterns
                + " safe_updates=" + safeUpdates + " rescans=" + rescans);
        System.out.println("PreLargeSeq: snapshots differing from the reference=" + badSnapshots + " cases=" + badCases);
        System.exit(badSnapshots == 0 ? 0 : 1);
    }
}
