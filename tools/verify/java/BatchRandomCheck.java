// SPDX-License-Identifier: MIT
package com.adaptive.itr.spm;

import java.math.BigDecimal;
import java.util.*;

/**
 * Checks the batch baseline (BatchTaSPM), the re-mine baseline (RemineTaSPM) and the engine of
 * the proposed method against the definition on random small databases. The reference is an
 * exhaustive enumeration written here: every subsequence of every target sequence is a
 * candidate; its support is the number of sequences that contain it (containment checked
 * directly) and it is output when support >= min_sup, it contains a target item and every gap
 * between consecutive containing positions (first gap from position 0, last gap to n) is at most
 * max_per.
 *
 * Databases: 5 to 12 sequences of 1 to 4 itemsets of 1 to 3 distinct items over {1..5}; target
 * set {1}; min_sup_rate in {0.2, 0.3, 0.5}; max_per in [2, n]; one snapshot holding all of them.
 *
 * Usage: java -cp <bench jar>:<classes> com.adaptive.itr.spm.BatchRandomCheck [cases] [seed]
 * Exit code 1 when any arm differs from the reference.
 */
public class BatchRandomCheck {

    static String key(List<int[]> p) {
        StringBuilder k = new StringBuilder();
        for (int q = 0; q < p.size(); q++) {
            if (q > 0) k.append('|');
            int[] is = p.get(q);
            for (int i = 0; i < is.length; i++) { if (i > 0) k.append(' '); k.append(is[i]); }
        }
        return k.toString();
    }

    static boolean subset(int[] small, int[] big) {
        int j = 0;
        for (int x : small) {
            while (j < big.length && big[j] < x) j++;
            if (j == big.length || big[j] != x) return false;
            j++;
        }
        return true;
    }

    static boolean contains(int[][] seq, List<int[]> p) {
        int i = 0;
        for (int[] is : p) {
            while (i < seq.length && !subset(is, seq[i])) i++;
            if (i == seq.length) return false;
            i++;
        }
        return true;
    }

    static void subsequences(int[][] seq, int from, List<int[]> cur, Map<String, List<int[]>> out) {
        if (!cur.isEmpty()) out.putIfAbsent(key(cur), new ArrayList<>(cur));
        for (int i = from; i < seq.length; i++) {
            int[] is = seq[i];
            for (int mask = 1; mask < (1 << is.length); mask++) {
                int[] sub = new int[Integer.bitCount(mask)];
                int u = 0;
                for (int b = 0; b < is.length; b++) if ((mask & (1 << b)) != 0) sub[u++] = is[b];
                cur.add(sub);
                subsequences(seq, i + 1, cur, out);
                cur.remove(cur.size() - 1);
            }
        }
    }

    static Map<String, Integer> reference(List<int[][]> db, List<Integer> lines, int n, int minSup, int maxPer, Set<Integer> T) {
        Map<String, List<int[]>> cand = new HashMap<>();
        for (int[][] s : db) subsequences(s, 0, new ArrayList<int[]>(), cand);
        Map<String, Integer> out = new TreeMap<>();
        for (Map.Entry<String, List<int[]>> c : cand.entrySet()) {
            List<int[]> p = c.getValue();
            boolean hasT = false;
            for (int[] is : p) for (int x : is) if (T.contains(x)) hasT = true;
            if (!hasT) continue;
            int sup = 0, prev = -1, maxGap = 0;
            for (int s = 0; s < db.size(); s++) {
                if (!contains(db.get(s), p)) continue;
                sup++;
                int pos = lines.get(s);
                maxGap = Math.max(maxGap, prev < 0 ? pos + 1 : pos - prev);
                prev = pos;
            }
            if (sup == 0) continue;
            maxGap = Math.max(maxGap, n - prev);
            if (sup >= minSup && maxGap <= maxPer) out.put(c.getKey(), sup);
        }
        return out;
    }

    public static void main(String[] a) {
        int cases = a.length > 0 ? Integer.parseInt(a[0]) : 2000;
        long seed = a.length > 1 ? Long.parseLong(a[1]) : 20261116L;
        Random rnd = new Random(seed);
        Set<Integer> T = new HashSet<>();
        T.add(1);
        AppConfig.TARGET_ITEMS = T;
        String[] rates = {"0.2", "0.3", "0.5"};
        int bad = 0, multi = 0, nonEmpty = 0;
        long patterns = 0;
        int[] diffs = new int[3];
        String[] names = {"Batch", "Re-mine", "engine"};
        for (int c = 0; c < cases; c++) {
            int n = 5 + rnd.nextInt(8);
            List<int[][]> db = new ArrayList<>();
            List<Integer> lines = new ArrayList<>();
            boolean hasMulti = false;
            for (int s = 0; s < n; s++) {
                int len = 1 + rnd.nextInt(4);
                int[][] seq = new int[len][];
                for (int i = 0; i < len; i++) {
                    TreeSet<Integer> is = new TreeSet<>();
                    int k = 1 + rnd.nextInt(3);
                    while (is.size() < k) is.add(1 + rnd.nextInt(5));
                    if (is.size() > 1) hasMulti = true;
                    seq[i] = new int[is.size()];
                    int u = 0;
                    for (int x : is) seq[i][u++] = x;
                }
                boolean t = false;
                for (int[] is : seq) for (int x : is) if (T.contains(x)) t = true;
                if (t) { db.add(seq); lines.add(s); }
            }
            String rate = rates[rnd.nextInt(rates.length)];
            int maxPer = 2 + rnd.nextInt(n - 1);
            AppConfig.MIN_SUP_RATE = Double.parseDouble(rate);
            AppConfig.MIN_SUP_RATE_EXACT = new BigDecimal(rate);
            AppConfig.MAX_PER = maxPer;
            int minSup = new BigDecimal(rate).multiply(BigDecimal.valueOf(n)).setScale(0, java.math.RoundingMode.CEILING).intValueExact();
            minSup = Math.max(1, minSup);
            Map<String, Integer> ref = reference(db, lines, n, minSup, maxPer, T);
            if (hasMulti) multi++;
            if (!ref.isEmpty()) nonEmpty++;
            patterns += ref.size();

            BatchTaSPM b = new BatchTaSPM();
            b.sink = new TreeMap<>();
            b.mineSnapshot(db, lines, n);
            RemineTaSPM r = new RemineTaSPM();
            r.sink = new TreeMap<>();
            r.mineSnapshot(db, lines, 0, n);
            IncAdaTaSPM.Engine e = new IncAdaTaSPM.Engine(new BigDecimal(rate), maxPer, T, 0.5, 0.1, 0.25, 0.25, 0.25);
            e.stubFrac(0.5).lazy(true).scoreConst(true);
            int[] pos = new int[lines.size()];
            for (int i = 0; i < pos.length; i++) pos[i] = lines.get(i) + 1;
            e.addBatch(db, pos, n);
            List<String> out = new ArrayList<>();
            List<Integer> sups = new ArrayList<>();
            e.collect(out, sups);
            Map<String, Integer> eng = new TreeMap<>();
            for (int i = 0; i < out.size(); i++) eng.put(out.get(i), sups.get(i));

            List<Map<String, Integer>> arms = Arrays.asList(b.sink, r.sink, eng);
            boolean any = false;
            for (int k = 0; k < 3; k++) {
                if (!arms.get(k).equals(ref)) {
                    diffs[k]++;
                    any = true;
                    if (diffs[k] <= 3) {
                        Set<String> miss = new TreeSet<>(ref.keySet()); miss.removeAll(arms.get(k).keySet());
                        Set<String> extra = new TreeSet<>(arms.get(k).keySet()); extra.removeAll(ref.keySet());
                        System.out.println("case " + c + ": " + names[k] + " differs; missing " + miss + " extra " + extra);
                    }
                }
            }
            if (any) bad++;
        }
        System.out.println("cases=" + cases + " seed=" + seed + " cases_with_multi_item_itemsets=" + multi
                + " cases_with_nonempty_reference=" + nonEmpty + " reference_patterns=" + patterns);
        for (int k = 0; k < 3; k++) System.out.println(names[k] + ": cases differing from the reference=" + diffs[k]);
        System.exit(bad == 0 ? 0 : 1);
    }
}
