package com.adaptive.itr.spm;
import java.io.*; import java.math.BigDecimal; import java.util.*;
/** java Bench data.txt sigma maxPer targets eta lam wV wR wS [nb] */
public class Bench {
    public static void main(String[] a) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(a[0]))) { String s; while ((s = br.readLine()) != null) lines.add(s); }
        Set<Integer> T = new HashSet<>(); for (String t : a[3].split(",")) T.add(Integer.parseInt(t.trim()));
        AppConfig.TARGET_ITEMS = T;
        int nb = a.length > 9 ? Integer.parseInt(a[9]) : 10;
        IncAdaTaSPM.Engine e = new IncAdaTaSPM.Engine(new BigDecimal(a[1]), Integer.parseInt(a[2]), T,
                Double.parseDouble(a[4]), Double.parseDouble(a[5]), Double.parseDouble(a[6]), Double.parseDouble(a[7]), Double.parseDouble(a[8]));
        int n = lines.size(), idx = 0; double cumT = 0;
        for (int b = 0; b < nb; b++) {
            int end = (b == nb - 1) ? n : (int) Math.round(n * (b + 1) / (double) nb);
            List<int[][]> seqs = new ArrayList<>(); List<Integer> ps = new ArrayList<>();
            for (; idx < end; idx++) {
                int[][] q = IncAdaTaSPM.parse(lines.get(idx));
                if (q != null && IncAdaTaSPM.containsTarget(q)) { seqs.add(q); ps.add(idx + 1); }
            }
            int[] pos = new int[ps.size()]; for (int i = 0; i < pos.length; i++) pos[i] = ps.get(i);
            long r0 = e.rescans; long t0 = System.nanoTime();
            e.addBatch(seqs, pos, end);
            double ms = (System.nanoTime() - t0) / 1e6; cumT += ms;
            long[] tc = e.treeCounts();
            System.out.printf(Locale.US, "B%d N=%d ms=%d t=%.0f cum=%.0f resc=%d promo=%d live=%d tomb=%d %s%n", b + 1, end, e.minSup(), ms, cumT, e.rescans - r0, e.promotions, tc[0], tc[1], e.prof());
        }
        if (a.length > 10) {
            List<String> out = new ArrayList<>(); List<Integer> sups = new ArrayList<>(); e.collect(out, sups);
            try (PrintWriter pw = new PrintWriter(a[10])) { for (int i = 0; i < out.size(); i++) pw.println(out.get(i) + " : sup = " + sups.get(i)); }
        }
    }
}
