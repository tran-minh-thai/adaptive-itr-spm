// SPDX-License-Identifier: MIT
package com.adaptive.itr.spm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the benchmark engine (the class itself, not a re-implementation) on the six-sequence
 * running example of the manuscript and prints its state after the initial database and after the
 * increment: output set, tracked nodes with support, stubs, tombstones and the bound b of every
 * tracked node, and the rescan and promotion counters.
 *
 * Items: A=1, B=2, C=3, D=4, E=5. Configuration of the proposed method: fixed bound (score
 * constant), phi = 0.5, eta = 0.5, lazy insertion; sigma = 0.6, max_reg = 3, T = {A};
 * D_init = S1..S4, one increment S5, S6.
 *
 * Usage: java -cp <bench jar>:<classes> com.adaptive.itr.spm.RunningExample
 */
public class RunningExample {

    static final String NAMES = "?ABCDE";

    static int[][] seq(int[]... itemsets) { return itemsets; }

    static String keyName(int key) {
        return ((key & 1) == 1 ? "S+" : "I+") + NAMES.charAt(key >> 1);
    }

    static String pattern(List<int[]> path) {
        StringBuilder sb = new StringBuilder("<");
        boolean open = false;
        for (int[] st : path) {
            if (st[1] == 1 || !open) { if (open) sb.append("),"); sb.append("("); open = true; }
            else sb.append(",");
            sb.append(NAMES.charAt(st[0]));
        }
        if (open) sb.append(")");
        return sb.append(">").toString();
    }

    static void dump(IncAdaTaSPM.Engine e, int P, ArrayList<int[]> path) {
        IncAdaTaSPM.Tree t = e.t;
        StringBuilder sb = new StringBuilder();
        sb.append(path.isEmpty() ? "root" : pattern(path)).append(" sup=").append(t.sup[P]).append(" b=").append(t.b[P]);
        int[] s = t.stub[P];
        sb.append(" stubs=[");
        if (s != null) for (int i = 0; i < s.length; i += 2) {
            path.add(new int[]{s[i] >> 1, s[i] & 1});
            sb.append(i == 0 ? "" : ", ").append(pattern(path)).append(":").append(s[i + 1]);
            path.remove(path.size() - 1);
        }
        sb.append("] tombstones=[");
        int[] d = t.dead[P];
        if (d != null) for (int i = 0; i < d.length; i++) {
            path.add(new int[]{d[i] >> 1, d[i] & 1});
            sb.append(i == 0 ? "" : ", ").append(pattern(path));
            path.remove(path.size() - 1);
        }
        sb.append("]");
        System.out.println("  " + sb);
        int[] kids = t.kid[P];
        if (kids == null) return;
        for (int c : kids) {
            path.add(new int[]{t.key[c] >> 1, t.key[c] & 1});
            dump(e, c, path);
            path.remove(path.size() - 1);
        }
    }

    static void report(IncAdaTaSPM.Engine e, String step) {
        List<String> out = new ArrayList<>();
        List<Integer> sups = new ArrayList<>();
        e.collect(out, sups);
        System.out.println("== after " + step + ": N=" + e.N + " min_sup=" + e.curMinSup + " floor=" + e.stubFloor
                + " rescans=" + e.rescans + " promotions=" + e.promotions + " projections=" + e.projections);
        for (int i = 0; i < out.size(); i++) System.out.println("  output " + out.get(i) + " sup=" + sups.get(i));
        dump(e, IncAdaTaSPM.Engine.ROOT, new ArrayList<int[]>());
    }

    public static void main(String[] a) {
        int A = 1, B = 2, C = 3, D = 4, E = 5;
        int[][][] db = {
            seq(new int[]{A, B}, new int[]{C}, new int[]{A, D}),
            seq(new int[]{A}, new int[]{C, D, E}),
            seq(new int[]{B}, new int[]{C}, new int[]{E}),
            seq(new int[]{A, B}, new int[]{C}, new int[]{B}),
            seq(new int[]{C}, new int[]{A, B}),
            seq(new int[]{A, B, C}, new int[]{D}),
        };
        Set<Integer> T = new HashSet<>();
        T.add(A);
        AppConfig.TARGET_ITEMS = T;
        IncAdaTaSPM.Engine e = new IncAdaTaSPM.Engine(new BigDecimal("0.6"), 3, T, 0.5, 0.1, 0.25, 0.25, 0.25);
        e.stubFrac(0.5).lazy(true).scoreConst(true);
        int[][] ends = {{0, 4}, {4, 6}};
        String[] steps = {"D_init (S1..S4)", "increment (S5, S6)"};
        for (int b = 0; b < ends.length; b++) {
            List<int[][]> seqs = new ArrayList<>();
            List<Integer> ps = new ArrayList<>();
            for (int i = ends[b][0]; i < ends[b][1]; i++) if (IncAdaTaSPM.containsTarget(db[i])) { seqs.add(db[i]); ps.add(i + 1); }
            int[] pos = new int[ps.size()];
            for (int i = 0; i < pos.length; i++) pos[i] = ps.get(i);
            e.addBatch(seqs, pos, ends[b][1]);
            report(e, steps[b]);
        }
    }
}
