// SPDX-License-Identifier: MIT
package com.adaptive.itr.spm;
import java.math.BigDecimal;
import java.util.*;
/**
 * Runs the three baselines (Re-mine, Batch, Item pre-large) and the benchmark engine, the classes
 * themselves, on three four-sequence databases and compares their output sets. Items: A=1, B=2,
 * C=3; T = {A}; min_sup_rate 0.5 (min_sup 2); max_per 3; one batch holding all four sequences.
 *
 * Case 1: each sequence is <(A),(A,B)>; the pattern <(A,B)> occurs only in the second itemset
 * that holds A. Case 2 (control): <(A,B),(A)>, where <(A,B)> is in the first itemset holding A.
 * Case 3: <(C),(A),(C),(A,B)>, the same situation after an S-extension.
 *
 * Usage: java -cp <bench jar>:<classes> com.adaptive.itr.spm.BaselineFirstOccurrence
 */
public class BaselineFirstOccurrence {
    static Set<String> diff(Map<String,Integer> a, Map<String,Integer> b) { Set<String> d = new TreeSet<>(a.keySet()); d.removeAll(b.keySet()); return d; }
    public static void main(String[] a) {
        int A = 1, B = 2, C = 3;
        // case 1: (A,B) only in the second itemset that holds A -> first-occurrence projection of A misses it
        // case 2 (control): (A,B) in the first itemset that holds A
        int[][][][] cases = {
            { {{A},{A,B}}, {{A},{A,B}}, {{A},{A,B}}, {{A},{A,B}} },
            { {{A,B},{A}}, {{A,B},{A}}, {{A,B},{A}}, {{A,B},{A}} },
            { {{C},{A},{C},{A,B}}, {{C},{A},{C},{A,B}}, {{C},{A},{C},{A,B}}, {{C},{A},{C},{A,B}} },
        };
        AppConfig.MIN_SUP_RATE = 0.5; AppConfig.MIN_SUP_RATE_EXACT = new BigDecimal("0.5"); AppConfig.MAX_PER = 3;
        Set<Integer> T = new HashSet<>(); T.add(A); AppConfig.TARGET_ITEMS = T;
        for (int c = 0; c < cases.length; c++) {
            List<int[][]> db = new ArrayList<>(); List<Integer> li = new ArrayList<>();
            for (int i = 0; i < cases[c].length; i++) { db.add(cases[c][i]); li.add(i); }
            BatchTaSPM b = new BatchTaSPM(); b.sink = new TreeMap<>();
            b.mineSnapshot(db, li, db.size());
            IncAdaTaSPM.Engine e = new IncAdaTaSPM.Engine(new BigDecimal("0.5"), 3, T, 0.5, 0.1, 0.25, 0.25, 0.25);
            e.stubFrac(0.5).lazy(true).scoreConst(true);
            int[] pos = new int[db.size()]; for (int i = 0; i < pos.length; i++) pos[i] = i + 1;
            e.addBatch(db, pos, db.size());
            List<String> out = new ArrayList<>(); List<Integer> sups = new ArrayList<>(); e.collect(out, sups);
            RemineTaSPM r = new RemineTaSPM(); r.sink = new TreeMap<>(); r.mineSnapshot(db, li, 0, db.size());
            ItemPreLargeTaSPM p = new ItemPreLargeTaSPM(); p.sink = new TreeMap<>(); p.addBatch(db, li, 0, db.size());
            Map<String,Integer> eng = new TreeMap<>(); for (int i = 0; i < out.size(); i++) eng.put(out.get(i), sups.get(i));
            System.out.println("case " + (c + 1) + ": engine " + eng.size() + " patterns; Re-mine " + (r.sink.equals(eng) ? "equal" : "DIFFERS " + r.sink)
                + "; Batch " + (b.sink.equals(eng) ? "equal" : "DIFFERS, missing " + diff(eng, b.sink))
                + "; Item pre-large " + (p.sink.equals(eng) ? "equal" : "DIFFERS, missing " + diff(eng, p.sink)));
        }
    }
}
