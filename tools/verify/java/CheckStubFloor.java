package com.adaptive.itr.spm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Checks the stub-floor statements against the engine itself (the engine class is not modified;
 * this subclass only records when each node was last expanded and inspects the tree after each
 * batch). Notation: floor_t = max(2, ceil(phi * min_sup_t)); for a live node P, e(P) is the size
 * of D_T when P was last expanded (built or re-projected), and k = |D_T| - e(P).
 *
 *  (B) after every batch, for every live node P:   b(P) <= floor_t - 1 + k
 *  (R) whenever a rescan of P is decided in batch t: k >= min_sup_t - floor_t + 1
 *  (S) after every batch, every stub of every live node carries the exact support of its pattern
 *      on D_T (recounted from the data; random cases only, where the recount is cheap)
 *  (L) every stub value is at least floor of the first batch (a stub never goes below the floor
 *      it was created with, and floors do not decrease); the number of stubs below the current
 *      floor is reported, because a node marked for a promotion keeps such stubs until its next
 *      visit
 *
 * Usage:
 *   java -cp <jar or classes>:<this> com.adaptive.itr.spm.CheckStubFloor random <cases> <seed> [mutant]
 *   java -cp <jar or classes>:<this> com.adaptive.itr.spm.CheckStubFloor stream <config> <arm> [mutant]
 *   (stream mode takes further key=value configuration overrides after the mutant, e.g. phi=0.25)
 * arm: IncAdaTaSPM-StaticPreLarge | IncAdaTaSPM.
 * mutant: none | extra-rescan | inflate-b | miscount-stub | stub-below-floor.
 * Exit code 0 when no statement is violated and every statement was tested at least once.
 */
public class CheckStubFloor {

    static final class Checked extends IncAdaTaSPM.Engine {
        final String mutant;
        int[] lastExp = new int[1024];
        long rChecks, rViol, bChecks, bViol, sChecks, sViol, lChecks, lViol, stale;
        int firstFloor = -1;
        boolean recount;

        Checked(BigDecimal rate, int maxPer, Set<Integer> T, double eta, double lam, double w1, double w2, double w3, String mutant) {
            super(rate, maxPer, T, eta, lam, w1, w2, w3);
            this.mutant = mutant;
        }

        @Override void expand(int P, IncAdaTaSPM.Proj pp, int d) {
            if (P >= lastExp.length) lastExp = Arrays.copyOf(lastExp, Math.max(P + 1, lastExp.length * 2));
            lastExp[P] = nDT;
            super.expand(P, pp, d);
            if (mutant.equals("inflate-b")) t.b[P] += stubFloor;
            if (mutant.equals("stub-below-floor") && t.stub[P] != null) t.stub[P][1] = 1;  // corrupts one stub value
        }

        @Override int stubOrMax(int P, int k, int cnt, int maxv) {
            if (mutant.equals("extra-rescan") && cnt > 0 && t.findKid(P, k) < 0 && !t.isDead(P, k) && t.findStub(P, k) < 0)
                return Math.max(maxv, curMinSup);
            if (mutant.equals("miscount-stub") && t.findStub(P, k) >= 0 && t.findKid(P, k) < 0) cnt += 1;
            return super.stubOrMax(P, k, cnt, maxv);
        }

        @Override void markUp(int P, byte flag) {
            if ((flag & IncAdaTaSPM.Tree.RESC) != 0) {
                rChecks++;
                int k = nDT - lastExp[P];
                if (k < curMinSup - stubFloor + 1) rViol++;
            }
            super.markUp(P, flag);
        }

        void inspect() {
            if (firstFloor < 0) firstFloor = stubFloor;
            visit(ROOT, new ArrayList<int[]>());
        }

        void visit(int P, ArrayList<int[]> path) {
            bChecks++;
            if (t.b[P] > stubFloor - 1 + (nDT - lastExp[P])) bViol++;
            int[] s = t.stub[P];
            if (s != null) {
                for (int i = 0; i < s.length; i += 2) {
                    lChecks++;
                    if (s[i + 1] < firstFloor) lViol++;
                    if (s[i + 1] < stubFloor) stale++;
                    if (recount) {
                        sChecks++;
                        path.add(new int[]{s[i] >> 1, s[i] & 1});
                        if (support(path) != s[i + 1]) sViol++;
                        path.remove(path.size() - 1);
                    }
                }
            }
            int[] kids = t.kid[P];
            if (kids == null) return;
            for (int c : kids) {
                path.add(new int[]{t.key[c] >> 1, t.key[c] & 1});
                visit(c, path);
                path.remove(path.size() - 1);
            }
        }

        /** Support on D_T of the pattern given as (item, sequence-extension flag) steps. */
        int support(List<int[]> path) {
            List<Set<Integer>> pat = new ArrayList<>();
            for (int[] st : path) {
                if (pat.isEmpty() || st[1] == 1) pat.add(new HashSet<Integer>());
                pat.get(pat.size() - 1).add(st[0]);
            }
            int c = 0;
            for (int i = 0; i < nDT; i++) {
                int[][] q = db[i];
                int j = 0;
                for (int[] itemset : q) {
                    if (j == pat.size()) break;
                    boolean all = true;
                    for (int x : pat.get(j)) {
                        boolean f = false;
                        for (int y : itemset) if (y == x) { f = true; break; }
                        if (!f) { all = false; break; }
                    }
                    if (all) j++;
                }
                if (j == pat.size()) c++;
            }
            return c;
        }
    }

    static long[] tot = new long[9];

    static void add(Checked e) {
        long[] v = {e.rChecks, e.rViol, e.bChecks, e.bViol, e.sChecks, e.sViol, e.lChecks, e.lViol, e.stale};
        for (int i = 0; i < 9; i++) tot[i] += v[i];
    }

    public static void main(String[] a) throws Exception {
        String mode = a[0];
        String mutant = a.length > 3 ? a[3] : "none";
        if (mode.equals("random")) {
            int cases = Integer.parseInt(a[1]);
            Random r = new Random(Long.parseLong(a[2]));
            double[] phis = {0.25, 0.5, 0.75, 1.0};
            for (int c = 0; c < cases; c++) {
                int alpha = 4 + r.nextInt(4);
                Set<Integer> T = new HashSet<>(); T.add(1);
                AppConfig.TARGET_ITEMS = T;
                int init = 10 + r.nextInt(30), K = 3 + r.nextInt(10), per = 1 + r.nextInt(8);
                int total = init + K * per;
                BigDecimal sigma = new BigDecimal(String.valueOf(new double[]{0.05, 0.1, 0.15, 0.2, 0.3}[r.nextInt(5)]));
                int maxPer = 2 + r.nextInt(total);
                double eta = new double[]{0, 0.3, 0.5, 0.9}[r.nextInt(4)];
                Checked e = new Checked(sigma, maxPer, T, eta, 0.1, 0.25, 0.25, 0.25, mutant);
                e.stubFrac(phis[r.nextInt(4)]).lazy(r.nextBoolean()).scoreConst(r.nextBoolean());
                e.recount = true;
                List<int[][]> all = new ArrayList<>();
                for (int i = 0; i < total; i++) {
                    int ni = 1 + r.nextInt(4);
                    int[][] q = new int[ni][];
                    for (int j = 0; j < ni; j++) {
                        Set<Integer> its = new HashSet<>();
                        int m = 1 + r.nextInt(3);
                        while (its.size() < m) its.add(1 + r.nextInt(alpha));
                        q[j] = its.stream().mapToInt(Integer::intValue).sorted().toArray();
                    }
                    all.add(q);
                }
                int done = 0;
                for (int b = 0; b <= K; b++) {
                    int end = b == 0 ? init : init + b * per;
                    List<int[][]> seqs = new ArrayList<>(); List<Integer> ps = new ArrayList<>();
                    for (int i = done; i < end; i++) if (IncAdaTaSPM.containsTarget(all.get(i))) { seqs.add(all.get(i)); ps.add(i + 1); }
                    int[] pos = new int[ps.size()]; for (int i = 0; i < pos.length; i++) pos[i] = ps.get(i);
                    e.addBatch(seqs, pos, end);
                    e.inspect();
                    done = end;
                }
                add(e);
            }
        } else if (mode.equals("stream")) {
            AppConfig.CURRENT_CONFIG_FILE = AppConfig.resolveConfig(a[1]).getAbsolutePath();
            AppConfig.load();
            for (int i = 4; i < a.length; i++) {
                String k = a[i].substring(0, a[i].indexOf('=')), v = a[i].substring(a[i].indexOf('=') + 1);
                if (!AppConfig.set(k, v)) throw new IllegalArgumentException("unknown option " + k);
            }
            String arm = a[2];
            StreamBench.Data d = StreamBench.load(AppConfig.DATASET_PATH);
            int[] ends = AppConfig.batchEnds(d.total);
            Checked e = new Checked(AppConfig.MIN_SUP_RATE_EXACT, AppConfig.MAX_PER, AppConfig.TARGET_ITEMS,
                    AppConfig.ETA, AppConfig.LAMBDA, AppConfig.W1, AppConfig.W3, AppConfig.W4, mutant);
            e.stubFrac(AppConfig.PHI).lazy(AppConfig.LAZY);
            if (arm.equals("IncAdaTaSPM-StaticPreLarge")) e.scoreConst(true);
            else if (!arm.equals("IncAdaTaSPM")) throw new IllegalArgumentException("arm " + arm);
            int ti = 0;
            for (int b = 0; b < ends.length; b++) {
                int n = ends[b];
                List<int[][]> batch = new ArrayList<>(); List<Integer> bl = new ArrayList<>();
                while (ti < d.seq.size() && d.line.get(ti) < n) { batch.add(d.seq.get(ti)); bl.add(d.line.get(ti)); ti++; }
                int[] positions = new int[bl.size()];
                for (int i = 0; i < positions.length; i++) positions[i] = bl.get(i) + 1;
                e.addBatch(batch, positions, n);
                e.inspect();
            }
            add(e);
            System.out.println("dataset=" + a[1] + " arm=" + arm + " phi=" + e.stubFrac() + " rescans=" + e.rescans + " promotions=" + e.promotions);
        } else throw new IllegalArgumentException("mode " + mode);
        System.out.printf("mutant=%s%n", mutant);
        System.out.printf("(R) rescan horizon: %d rescans checked, %d violations%n", tot[0], tot[1]);
        System.out.printf("(B) bound on b:     %d node checks, %d violations%n", tot[2], tot[3]);
        System.out.printf("(S) exact stubs:    %d stub recounts, %d violations%n", tot[4], tot[5]);
        System.out.printf("(L) stub >= first floor: %d stub checks, %d violations; stubs below the current floor: %d%n", tot[6], tot[7], tot[8]);
        boolean recountMode = mode.equals("random");
        boolean tested = tot[0] > 0 && tot[2] > 0 && tot[6] > 0 && (!recountMode || tot[4] > 0);
        boolean clean = tot[1] == 0 && tot[3] == 0 && tot[5] == 0 && tot[7] == 0;
        System.out.println(clean && tested ? "RESULT pass" : (!tested ? "RESULT untested statement" : "RESULT violation"));
        System.exit(clean && tested ? 0 : 1);
    }
}
