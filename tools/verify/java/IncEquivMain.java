package com.adaptive.itr.spm;
import java.io.*; import java.math.BigDecimal; import java.util.*;
/** Test harness: java IncEquivMain data.txt sizes sigma maxPer targets eta lam wV wR wS */
public class IncEquivMain {
    public static void main(String[] a) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(a[0]))) { String s; while ((s = br.readLine()) != null) lines.add(s); }
        String[] sz = a[1].split(",");
        Set<Integer> T = new HashSet<>(); for (String t : a[4].split(",")) T.add(Integer.parseInt(t));
        AppConfig.TARGET_ITEMS = T;
        IncAdaTaSPM.Engine e = new IncAdaTaSPM.Engine(new BigDecimal(a[2]), Integer.parseInt(a[3]), T,
                Double.parseDouble(a[5]), Double.parseDouble(a[6]), Double.parseDouble(a[7]), Double.parseDouble(a[8]), Double.parseDouble(a[9]));
        int idx = 0; StringBuilder sb = new StringBuilder();
        for (int b = 0; b < sz.length; b++) {
            int k = Integer.parseInt(sz[b]);
            List<int[][]> seqs = new ArrayList<>(); List<Integer> ps = new ArrayList<>();
            for (int i = 0; i < k; i++, idx++) {
                int[][] q = IncAdaTaSPM.parse(lines.get(idx));
                if (q != null && IncAdaTaSPM.containsTarget(q)) { seqs.add(q); ps.add(idx + 1); }
            }
            int[] pos = new int[ps.size()]; for (int i = 0; i < pos.length; i++) pos[i] = ps.get(i);
            e.addBatch(seqs, pos, idx);
            List<String> out = new ArrayList<>(); e.collect(out, null); Collections.sort(out);
            sb.append("B").append(b).append(' ').append(String.join(",", out)).append('\n');
        }
        sb.append("RESCANS ").append(e.rescans).append('\n');
        System.out.print(sb);
    }
}
