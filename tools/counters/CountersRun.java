// SPDX-License-Identifier: MIT
package com.adaptive.itr.spm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Deterministic counter run of the proposed method (IncAdaTaSPM-StaticPreLarge, the
 * configuration of the paper) with the counted copy of the engine. Streams one configuration
 * like StreamBench (D_init then the increments) and writes one row per batch.
 *
 * Usage: CountersRun <stream config> <output csv> [measured live csv to compare]
 * With a measured csv, the counters that the measured engine also records (patterns, rescans,
 * rescan_inserts, promotions, projections, live_nodes, tombstones, stubs) are compared batch by
 * batch; exit code 3 on any difference, 2 when no batch could be compared.
 */
public class CountersRun {
    public static void main(String[] a) throws Exception {
        File cfg = AppConfig.resolveConfig(a[0]);
        AppConfig.CURRENT_CONFIG_FILE = cfg.getAbsolutePath();
        AppConfig.load();
        StreamBench.Data d = StreamBench.load(AppConfig.DATASET_PATH);
        int[] ends = AppConfig.batchEnds(d.total);
        IncAdaTaSPMCounted.Engine e = new IncAdaTaSPMCounted.Engine(AppConfig.MIN_SUP_RATE_EXACT, AppConfig.MAX_PER, AppConfig.TARGET_ITEMS,
                AppConfig.ETA, AppConfig.LAMBDA, AppConfig.W1, AppConfig.W3, AppConfig.W4)
                .stubFrac(AppConfig.PHI).lazy(AppConfig.LAZY).scoreConst(true).targetProximityWeight(AppConfig.W_TBR);
        Map<Integer, String[]> ref = new HashMap<>();
        String[] refHead = null;
        if (a.length > 2) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(a[2]), StandardCharsets.UTF_8))) {
                refHead = br.readLine().split(",", -1);
                String l;
                while ((l = br.readLine()) != null) { String[] f = l.split(",", -1); if (f[f.length - 1].equals("OK")) ref.put(Integer.parseInt(f[5]), f); }
            }
        }
        String[] cmp = {"patterns", "rescans", "rescan_inserts", "promotions", "projections", "live_nodes", "tombstones", "stubs"};
        long compared = 0, diff = 0;
        long[] prevBy = new long[5];
        long pr = 0, pi = 0, pp = 0, pj = 0;
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(a[1]), StandardCharsets.UTF_8))) {
            w.println("batch,n_total,min_sup,patterns,rescans,rescan_inserts,promotions,projections,proj_initial,proj_walk_increment,proj_rescan,proj_promotion,proj_ancestor,live_nodes,tombstones,stubs,max_rescans_one_node");
            int ti = 0;
            for (int b = 0; b < ends.length; b++) {
                int n = ends[b];
                List<int[][]> batch = new ArrayList<>();
                List<Integer> bl = new ArrayList<>();
                while (ti < d.seq.size() && d.line.get(ti) < n) { batch.add(d.seq.get(ti)); bl.add(d.line.get(ti)); ti++; }
                int[] pos = new int[bl.size()];
                for (int i = 0; i < pos.length; i++) pos[i] = bl.get(i) + 1;
                e.addBatch(batch, pos, n);
                long pats = e.countOutput();
                long[] tc = e.treeCounts();
                long[] by = new long[5];
                for (int k = 0; k < 5; k++) { by[k] = e.projBy[k] - prevBy[k]; prevBy[k] = e.projBy[k]; }
                long[] mine = {pats, e.rescans - pr, e.rescanInserts - pi, e.promotions - pp, e.projections - pj, tc[0], tc[1], tc[2]};
                pr = e.rescans; pi = e.rescanInserts; pp = e.promotions; pj = e.projections;
                w.println(b + "," + n + "," + e.minSup() + "," + pats + "," + mine[1] + "," + mine[2] + "," + mine[3] + "," + mine[4] + ","
                        + by[0] + "," + by[1] + "," + by[2] + "," + by[3] + "," + by[4] + "," + tc[0] + "," + tc[1] + "," + tc[2] + "," + e.maxRescOneNode);
                long sum = by[0] + by[1] + by[2] + by[3] + by[4];
                if (sum != mine[4]) { System.out.println("batch " + b + ": projections by source sum to " + sum + ", total " + mine[4]); diff++; }
                String[] f = ref.get(b);
                if (f != null) {
                    compared++;
                    for (int k = 0; k < cmp.length; k++) {
                        int col = Arrays.asList(refHead).indexOf(cmp[k]);
                        if (Long.parseLong(f[col]) != mine[k]) { diff++; System.out.println("batch " + b + " " + cmp[k] + ": measured " + f[col] + ", counted copy " + mine[k]); }
                    }
                }
            }
        }
        System.out.println("batches=" + ends.length + " compared_with_measured=" + compared + " differences=" + diff + " max_rescans_one_node=" + e.maxRescOneNode);
        if (diff > 0) System.exit(3);
        if (a.length > 2 && compared == 0) System.exit(2);
    }
}
