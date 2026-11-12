package com.adaptive.itr.spm;
import java.io.*; import java.math.BigDecimal; import java.util.*;
/** Reads cases: "CASE sizes sigma maxPer targets eta lam wV wR wS" then sum(sizes) SPMF lines. */
public class IncEquivMulti {
    public static void main(String[] a) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(a[0]));
        PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(a[1])));
        String h;
        while ((h = br.readLine()) != null) {
            String[] f = h.trim().split(" ");
            String[] sz = f[1].split(",");
            Set<Integer> T = new HashSet<>(); for (String t : f[4].split(",")) T.add(Integer.parseInt(t));
            AppConfig.TARGET_ITEMS = T;
            String mut = System.getProperty("mutant", "");
            BigDecimal sg = new BigDecimal(f[2]); int mp = Integer.parseInt(f[3]);
            double eta = Double.parseDouble(f[5]), lam = Double.parseDouble(f[6]), w1 = Double.parseDouble(f[7]), w2 = Double.parseDouble(f[8]), w3 = Double.parseDouble(f[9]);
            IncAdaTaSPM.Engine e;
            if (mut.equals("no-rescan")) e = new IncAdaTaSPM.Engine(sg, mp, T, eta, lam, w1, w2, w3) {
                @Override void markUp(int P, byte flag) { super.markUp(P, (byte) (flag & ~IncAdaTaSPM.Tree.RESC)); } };
            else if (mut.equals("no-promotion")) e = new IncAdaTaSPM.Engine(sg, mp, T, eta, lam, w1, w2, w3) {
                @Override void promote(int P, IncAdaTaSPM.Proj pp, int d) { } };
            else if (mut.isEmpty()) e = new IncAdaTaSPM.Engine(sg, mp, T, eta, lam, w1, w2, w3);
            else throw new IllegalArgumentException("unknown mutant " + mut);
            int idx = 0;
            for (int b = 0; b < sz.length; b++) {
                int k = Integer.parseInt(sz[b]);
                List<int[][]> seqs = new ArrayList<>(); List<Integer> ps = new ArrayList<>();
                for (int i = 0; i < k; i++, idx++) {
                    int[][] q = IncAdaTaSPM.parse(br.readLine());
                    if (q != null && IncAdaTaSPM.containsTarget(q)) { seqs.add(q); ps.add(idx + 1); }
                }
                int[] pos = new int[ps.size()]; for (int i = 0; i < pos.length; i++) pos[i] = ps.get(i);
                e.addBatch(seqs, pos, idx);
                List<String> out = new ArrayList<>(); e.collect(out, null); Collections.sort(out);
                pw.println("B" + b + " " + String.join(",", out));
            }
            pw.println("RESCANS " + e.rescans);
        }
        pw.close();
    }
}
