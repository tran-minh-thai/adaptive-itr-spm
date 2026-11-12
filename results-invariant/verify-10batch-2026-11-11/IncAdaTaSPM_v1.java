package com.adaptive.itr.spm;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ada-IncTaSPM, incremental version (design file ThietKe_GiaTangThat.md, sections 2 to 4).
 *
 * <p>State kept across batches: the filtered database D_T, per-item occurrence lists, and a
 * prefix tree of tracked patterns. Each tree node stores support, last occurrence position,
 * the largest closed gap g, Welford statistics of the gaps, a dead flag (tombstone) and an
 * upper bound b on the support of its untracked children. A batch scans only the new
 * sequences, except at nodes where b plus the batch support of an untracked child can reach
 * min_sup; those nodes are re-projected on the full database (a "rescan").
 *
 * <p>min_sup = max(1, ceil(rate * N)) is computed in exact decimal arithmetic.
 */
public class IncAdaTaSPM {

    // =====================================================================================
    // Engine
    // =====================================================================================

    /** Growable int array. */
    static final class IntList {
        int[] a = new int[4];
        int n = 0;
        void add(int v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
    }

    /** Projection of a pattern: sequences that contain it and, per sequence, the itemset
     *  indices where its last itemset is matched after a valid embedding of the prefix. */
    static final class Proj {
        int size;
        int[] sids;
        int[] offs;   // size + 1 entries
        int[] tids;
    }

    static final class Node {
        final Node parent;
        final int item;
        final boolean sExt;
        HashMap<Integer, Node> kids;
        boolean dead, hasT;
        int sup, last, g, wc;
        double wm, wM2;
        int b;
        long anchorN;
        int anchorB;
        int stamp, supDelta;
        double rsum;

        Node(Node parent, int item, boolean sExt) { this.parent = parent; this.item = item; this.sExt = sExt; }

        void addPos(int p) {
            int gap = p - last;
            if (gap > g) g = gap;
            wc++;
            double d = gap - wm;
            wm += d / wc;
            wM2 += d * (gap - wm);
            last = p;
            sup++;
        }
    }

    static int key(int item, boolean sExt) { return item * 2 + (sExt ? 1 : 0); }

    public static final class Engine {
        final BigDecimal rate;
        final int maxPer;
        final Set<Integer> T;
        final double eta, lam, wV, wR, wS;

        int[][][] db = new int[1024][][];
        int[] pos = new int[1024];
        int nDT = 0;
        long N = 0;

        IntList[] occSids = new IntList[0], occOffs = new IntList[0], occTids = new IntList[0];
        int maxItem = -1;

        final Node root = new Node(null, -1, false);
        boolean first = true;
        public long rescans = 0;
        int nDold, nDnew, deltaStart, curMinSup, batchId = 0;
        long posEnd;

        // local counting buffers
        int[] cntI = new int[0], cntS = new int[0], seenI = new int[0], seenS = new int[0];
        int[] trI = new int[0], trS = new int[0];

        public Engine(BigDecimal rate, int maxPer, Set<Integer> T, double eta, double lam,
                      double wV, double wR, double wS) {
            if (!(eta >= 0 && eta < 1)) throw new IllegalArgumentException("eta must be in [0,1)");
            this.rate = rate; this.maxPer = maxPer; this.T = T; this.eta = eta; this.lam = lam;
            if (wV + wR + wS <= 0) { wV = 1; wR = 1; wS = 1; }
            this.wV = wV; this.wR = wR; this.wS = wS;
        }

        public int minSup() { return curMinSup; }

        int minSupOf(long n) {
            BigDecimal v = rate.multiply(BigDecimal.valueOf(n)).setScale(0, RoundingMode.CEILING);
            return Math.max(1, v.intValueExact());
        }

        // ---------------------------------------------------------------- storage
        void ensureItem(int x) {
            if (x <= maxItem) return;
            int cap = Math.max(x + 1, occSids.length * 2);
            occSids = Arrays.copyOf(occSids, cap); occOffs = Arrays.copyOf(occOffs, cap); occTids = Arrays.copyOf(occTids, cap);
            for (int i = maxItem + 1; i < cap; i++) { occSids[i] = new IntList(); occOffs[i] = new IntList(); occTids[i] = new IntList(); }
            cntI = Arrays.copyOf(cntI, cap); cntS = Arrays.copyOf(cntS, cap);
            int old = seenI.length;
            seenI = Arrays.copyOf(seenI, cap); seenS = Arrays.copyOf(seenS, cap);
            for (int i = old; i < cap; i++) { seenI[i] = -1; seenS[i] = -1; }
            trI = Arrays.copyOf(trI, cap); trS = Arrays.copyOf(trS, cap);
            maxItem = cap - 1;
        }

        void appendSeq(int[][] seq, int position) {
            if (nDT == db.length) { db = Arrays.copyOf(db, nDT * 2); pos = Arrays.copyOf(pos, nDT * 2); }
            int sid = nDT++;
            db[sid] = seq; pos[sid] = position;
            for (int t = 0; t < seq.length; t++) {
                for (int x : seq[t]) {
                    ensureItem(x);
                    IntList s = occSids[x];
                    if (s.n == 0 || s.a[s.n - 1] != sid) { s.add(sid); occOffs[x].add(occTids[x].n); }
                    IntList tl = occTids[x];
                    if (tl.n == 0 || occOffs[x].a[occOffs[x].n - 1] == tl.n || tl.a[tl.n - 1] != t) tl.add(t);
                }
            }
        }

        static boolean has(int[] itemset, int x) {
            if (itemset.length >= 8) return Arrays.binarySearch(itemset, x) >= 0;
            for (int v : itemset) { if (v == x) return true; if (v > x) return false; }
            return false;
        }

        /** Projection of the 1-item pattern x restricted to sids >= fromSid. */
        Proj itemProj(int x, int fromSid) {
            IntList s = occSids[x], o = occOffs[x], tl = occTids[x];
            int lo = 0, hi = s.n;
            while (lo < hi) { int m = (lo + hi) >>> 1; if (s.a[m] < fromSid) lo = m + 1; else hi = m; }
            Proj p = new Proj();
            p.size = s.n - lo;
            p.sids = Arrays.copyOfRange(s.a, lo, s.n);
            p.offs = new int[p.size + 1];
            int base = p.size > 0 ? o.a[lo] : 0;
            for (int i = 0; i < p.size; i++) p.offs[i] = o.a[lo + i] - base;
            p.offs[p.size] = tl.n - base;
            p.tids = Arrays.copyOfRange(tl.a, base, tl.n);
            return p;
        }

        Proj extend(Proj p, int x, boolean sExt) {
            IntList sids = new IntList(), offs = new IntList(), tids = new IntList();
            for (int i = 0; i < p.size; i++) {
                int sid = p.sids[i];
                int[][] seq = db[sid];
                int start = tids.n;
                if (sExt) {
                    int minP = p.tids[p.offs[i]];
                    for (int t = minP + 1; t < seq.length; t++) if (has(seq[t], x)) tids.add(t);
                } else {
                    for (int k = p.offs[i]; k < p.offs[i + 1]; k++) { int t = p.tids[k]; if (has(seq[t], x)) tids.add(t); }
                }
                if (tids.n > start) { sids.add(sid); offs.add(start); }
            }
            Proj c = new Proj();
            c.size = sids.n;
            c.sids = Arrays.copyOf(sids.a, sids.n);
            c.offs = Arrays.copyOf(offs.a, sids.n + 1);
            c.offs[sids.n] = tids.n;
            c.tids = Arrays.copyOf(tids.a, tids.n);
            return c;
        }

        /** Child counts of a non-root pattern with projection p. Result in trI/cntI (nI) and trS/cntS (nS). */
        int nI, nS;
        void countChildren(Proj p, int lastItem) {
            nI = 0; nS = 0;
            for (int i = 0; i < p.size; i++) {
                int sid = p.sids[i];
                int[][] seq = db[sid];
                for (int k = p.offs[i]; k < p.offs[i + 1]; k++) {
                    for (int x : seq[p.tids[k]]) {
                        if (x > lastItem && seenI[x] != sid) {
                            if (seenI[x] == -1) { trI[nI++] = x; cntI[x] = 0; }
                            seenI[x] = sid; cntI[x]++;
                        }
                    }
                }
                int minP = p.tids[p.offs[i]];
                for (int t = minP + 1; t < seq.length; t++) {
                    for (int x : seq[t]) {
                        if (seenS[x] != sid) {
                            if (seenS[x] == -1) { trS[nS++] = x; cntS[x] = 0; }
                            seenS[x] = sid; cntS[x]++;
                        }
                    }
                }
            }
        }

        void resetCounts() {
            for (int i = 0; i < nI; i++) seenI[trI[i]] = -1;
            for (int i = 0; i < nS; i++) seenS[trS[i]] = -1;
        }

        /** Child counts of the root over sids >= fromSid (1-item patterns). Result in trI/cntI. */
        void countRoot(int fromSid) {
            nI = 0; nS = 0;
            for (int sid = fromSid; sid < nDT; sid++) {
                for (int[] its : db[sid]) for (int x : its) {
                    if (seenI[x] != sid) {
                        if (seenI[x] == -1) { trI[nI++] = x; cntI[x] = 0; }
                        seenI[x] = sid; cntI[x]++;
                    }
                }
            }
        }

        // ---------------------------------------------------------------- scores
        double score(Node n, int supDelta, double rsum, int rcnt) {
            int old = n.sup - supDelta;
            double V;
            if (first || old == 0 || nDold == 0 || nDnew == 0) V = 1.0;
            else V = Math.min(1.0, ((double) supDelta / nDnew) / ((double) old / nDold));
            double R = rcnt > 0 ? rsum / rcnt : 0.0;
            double S;
            if (n.wc > 1 && n.wm > 0) {
                double sd = Math.sqrt(Math.max(0.0, n.wM2 / n.wc));
                S = 1.0 - Math.min(1.0, sd / n.wm);
            } else S = 0.0;
            return (wV * V + wR * R + wS * S) / (wV + wR + wS);
        }

        double LB(Node n, int supDelta, double rsum, int rcnt) {
            return curMinSup * (1 - eta * score(n, supDelta, rsum, rcnt));
        }

        boolean irregular(Node n) {
            return n.sup == 0 || Math.max(n.g, (long) (N + 1 - n.last)) > maxPer;
        }

        Node newNode(Node parent, int item, boolean sExt, Proj p) {
            Node c = new Node(parent, item, sExt);
            c.hasT = parent.hasT || T.contains(item);
            for (int i = 0; i < p.size; i++) c.addPos(pos[p.sids[i]]);
            return c;
        }

        // ---------------------------------------------------------------- expansion (steps 5 and 6)
        void expand(Node P, Proj pp) {
            if (P.kids == null) P.kids = new HashMap<>();
            double lbMin = curMinSup * (1 - eta);
            int[] items; boolean[] types; int[] counts; int m;
            if (P == root) {
                countRoot(0);
                m = nI; items = new int[m]; types = new boolean[m]; counts = new int[m];
                for (int i = 0; i < nI; i++) { items[i] = trI[i]; counts[i] = cntI[trI[i]]; }
            } else {
                countChildren(pp, P.item);
                m = nI + nS; items = new int[m]; types = new boolean[m]; counts = new int[m];
                for (int i = 0; i < nI; i++) { items[i] = trI[i]; counts[i] = cntI[trI[i]]; }
                for (int i = 0; i < nS; i++) { items[nI + i] = trS[i]; types[nI + i] = true; counts[nI + i] = cntS[trS[i]]; }
            }
            resetCounts();
            int bmax = 0;
            List<Node> nw = new ArrayList<>();
            List<Proj> nwp = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                int k = key(items[i], types[i]);
                if (P.kids.containsKey(k)) continue;
                if (counts[i] < lbMin) { if (counts[i] > bmax) bmax = counts[i]; continue; }
                Proj cp = (P == root) ? itemProj(items[i], 0) : extend(pp, items[i], types[i]);
                Node c = newNode(P, items[i], types[i], cp);
                if (c.sup < LB(c, first ? c.sup : 0, 0.0, 0)) { if (c.sup > bmax) bmax = c.sup; }
                else if (irregular(c)) { c.dead = true; P.kids.put(k, c); }
                else { P.kids.put(k, c); nw.add(c); nwp.add(cp); }
            }
            P.b = bmax; P.anchorN = N; P.anchorB = bmax;
            for (int i = 0; i < nw.size(); i++) expand(nw.get(i), nwp.get(i));
        }

        Proj fullProj(Node P) {
            ArrayList<Node> path = new ArrayList<>();
            for (Node n = P; n != root; n = n.parent) path.add(n);
            Proj p = null;
            for (int i = path.size() - 1; i >= 0; i--) {
                Node n = path.get(i);
                p = (p == null) ? itemProj(n.item, 0) : extend(p, n.item, n.sExt);
            }
            return p;
        }

        boolean attached(Node n) {
            while (n.parent != null) {
                if (n.dead) return false;
                Node par = n.parent;
                if (par.kids == null || par.kids.get(key(n.item, n.sExt)) != n) return false;
                n = par;
            }
            return true;
        }

        // ---------------------------------------------------------------- one batch
        final List<Node> rescanList = new ArrayList<>();

        /** Adds one batch. positions[i] is the 1-based original position of seqs[i]; newN is the
         *  number of original transactions after the batch (counting non-target ones). */
        public void addBatch(List<int[][]> seqs, int[] positions, long newN) {
            batchId++;
            nDold = nDT;
            deltaStart = nDT;
            for (int i = 0; i < seqs.size(); i++) appendSeq(seqs.get(i), positions[i]);
            nDnew = nDT - deltaStart;
            N = newN;
            posEnd = N + 1;
            curMinSup = minSupOf(N);

            if (first) {
                root.sup = nDT;
                root.hasT = false;
                expand(root, null);
                first = false;
                return;
            }
            root.sup += nDnew;

            // step 2 and step 4 decision
            rescanList.clear();
            walk(root, null);

            // step 3
            kill(root);

            // step 5
            prune(root);

            // steps 5 and 6 for rescanned nodes
            for (Node P : rescanList) {
                if (P != root && !attached(P)) continue;
                rescans++;
                expand(P, P == root ? null : fullProj(P));
            }
        }

        void walk(Node P, Proj pd) {
            int maxv = 0;
            if (P == root) {
                countRoot(deltaStart);
                for (int i = 0; i < nI; i++) {
                    int x = trI[i];
                    if (P.kids == null || !P.kids.containsKey(key(x, false))) maxv = Math.max(maxv, cntI[x]);
                }
            } else {
                countChildren(pd, P.item);
                for (int i = 0; i < nI; i++) { int x = trI[i]; if (P.kids == null || !P.kids.containsKey(key(x, false))) maxv = Math.max(maxv, cntI[x]); }
                for (int i = 0; i < nS; i++) { int x = trS[i]; if (P.kids == null || !P.kids.containsKey(key(x, true))) maxv = Math.max(maxv, cntS[x]); }
            }
            resetCounts();
            if (maxv > 0) {
                if (P.b + maxv >= curMinSup) rescanList.add(P);
                else P.b += maxv;
            }
            if (P.kids == null) return;
            for (Node c : P.kids.values()) {
                if (c.dead) continue;
                Proj cp = (P == root) ? itemProj(c.item, deltaStart) : extend(pd, c.item, c.sExt);
                if (cp.size == 0) continue;
                double rs = 0;
                for (int i = 0; i < cp.size; i++) {
                    int p = pos[cp.sids[i]];
                    c.addPos(p);
                    rs += Math.exp(-lam * (posEnd - p));
                }
                c.stamp = batchId; c.supDelta = cp.size; c.rsum = rs;
                walk(c, cp);
            }
        }

        void kill(Node P) {
            if (P.kids == null) return;
            for (Node c : P.kids.values()) {
                if (c.dead) continue;
                if (irregular(c)) { c.dead = true; c.kids = null; }
                else kill(c);
            }
        }

        void prune(Node P) {
            if (P.kids == null) return;
            ArrayList<Integer> rm = null;
            for (java.util.Map.Entry<Integer, Node> e : P.kids.entrySet()) {
                Node c = e.getValue();
                if (c.dead) continue;
                boolean cur = c.stamp == batchId;
                int sd = cur ? c.supDelta : 0;
                if (c.sup < LB(c, sd, cur ? c.rsum : 0.0, sd)) {
                    if (rm == null) rm = new ArrayList<>();
                    rm.add(e.getKey());
                    if (c.sup > P.b) P.b = c.sup;
                    P.anchorN = N; P.anchorB = P.b;
                } else prune(c);
            }
            if (rm != null) for (Integer k : rm) P.kids.remove(k);
        }

        // ---------------------------------------------------------------- output and counters
        public void collect(List<String> out, List<Integer> sups) { collect(root, new ArrayList<int[]>(), out, sups); }

        void collect(Node P, ArrayList<int[]> path, List<String> out, List<Integer> sups) {
            if (P.kids == null) return;
            for (Node c : P.kids.values()) {
                if (c.dead) continue;
                path.add(new int[]{c.item, c.sExt ? 1 : 0});
                if (c.sup >= curMinSup && c.hasT && Math.max(c.g, N + 1 - c.last) <= maxPer) {
                    out.add(patternString(path));
                    if (sups != null) sups.add(c.sup);
                }
                collect(c, path, out, sups);
                path.remove(path.size() - 1);
            }
        }

        static String patternString(ArrayList<int[]> path) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) sb.append(path.get(i)[1] == 1 ? "|" : " ");
                sb.append(path.get(i)[0]);
            }
            return sb.toString();
        }

        public int[] treeCounts() { int[] r = new int[2]; count(root, r); return r; }
        void count(Node P, int[] r) {
            if (P.kids == null) return;
            for (Node c : P.kids.values()) { if (c.dead) r[1]++; else { r[0]++; count(c, r); } }
        }
    }

    // =====================================================================================
    // Benchmark driver (same batching, filtering and timing scope as AdaIncTaSPM)
    // =====================================================================================

    private int fullTotalTransCount, validTotalDBSize;
    private final String OUTPUT_FILE = "result_IncAdaTaSPM.txt";

    public void run() {
        AppConfig.load();
        precount(AppConfig.DATASET_PATH);
        int numBatches = AppConfig.DATA_CHUNKS.length;
        int[] ends = new int[numBatches];
        double cum = 0;
        for (int b = 0; b < numBatches; b++) { cum += AppConfig.DATA_CHUNKS[b]; ends[b] = (int) Math.round(fullTotalTransCount * cum); }
        ends[numBatches - 1] = fullTotalTransCount;

        double[] time = new double[numBatches], mem = new double[numBatches];
        int[] pats = new int[numBatches];
        long[] resc = new long[numBatches];
        int[] live = new int[numBatches], tomb = new int[numBatches];
        int validRuns = 0;
        Engine last = null;
        for (int run = 1; run <= AppConfig.TOTAL_RUNS; run++) {
            boolean warm = run <= AppConfig.WARMUP_RUNS;
            boolean lastRun = run == AppConfig.TOTAL_RUNS;
            boolean exclLast = (AppConfig.TOTAL_RUNS - AppConfig.WARMUP_RUNS) > 1;
            boolean valid = !warm && !(lastRun && exclLast);
            Engine eng = new Engine(new BigDecimal(BigDecimal.valueOf(AppConfig.MIN_SUP_RATE).toPlainString()),
                    AppConfig.MAX_PER, AppConfig.TARGET_ITEMS, AppConfig.ETA, AppConfig.LAMBDA,
                    AppConfig.W1, AppConfig.W3, AppConfig.W4);
            double cumMs = 0;
            try (BufferedReader br = new BufferedReader(new FileReader(AppConfig.DATASET_PATH), 65536)) {
                int[] line = {0};
                for (int b = 0; b < numBatches; b++) {
                    List<int[][]> seqs = new ArrayList<>();
                    IntList posl = new IntList();
                    readBatch(br, line, ends[b], seqs, posl);
                    int[] positions = Arrays.copyOf(posl.a, posl.n);
                    System.gc();
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    long t0 = System.nanoTime();
                    eng.addBatch(seqs, positions, ends[b]);
                    long t1 = System.nanoTime();
                    cumMs += (t1 - t0) / 1e6;
                    Runtime rt = Runtime.getRuntime();
                    double mb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
                    if (valid) { time[b] += cumMs; mem[b] = Math.max(mem[b], mb); }
                    if (lastRun) {
                        List<String> o = new ArrayList<>();
                        eng.collect(o, null);
                        pats[b] = o.size();
                        resc[b] = eng.rescans;
                        int[] tc = eng.treeCounts();
                        live[b] = tc[0]; tomb[b] = tc[1];
                    }
                    System.out.printf(Locale.US, "  [Batch %d/%d] N=%d minSup=%d time=%.2f ms cum=%.2f ms RAM=%.2f MB rescans=%d%n",
                            b + 1, numBatches, (long) ends[b], eng.minSup(), (t1 - t0) / 1e6, cumMs, mb, eng.rescans);
                }
            } catch (IOException e) { e.printStackTrace(); }
            if (valid) validRuns++;
            last = eng;
        }
        writeReport(last, numBatches, time, mem, pats, resc, live, tomb, Math.max(1, validRuns));
    }

    private void precount(String path) {
        int cur = 0, valid = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(path), 65536)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isEmpty() && line.charAt(0) != '@') {
                    int[][] s = parse(line);
                    if (s != null && containsTarget(s)) valid++;
                }
                cur++;
            }
        } catch (IOException e) { e.printStackTrace(); }
        fullTotalTransCount = cur; validTotalDBSize = valid;
    }

    static boolean containsTarget(int[][] s) {
        for (int[] its : s) for (int x : its) if (AppConfig.TARGET_ITEMS.contains(x)) return true;
        return false;
    }

    /** Parses one SPMF line; itemsets are sorted and de-duplicated. Returns null if empty. */
    static int[][] parse(String line) {
        ArrayList<int[]> seq = new ArrayList<>();
        IntList cur = new IntList();
        for (String tok : line.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            int v = Integer.parseInt(tok);
            if (v == -2) break;
            if (v == -1) {
                if (cur.n > 0) {
                    int[] its = Arrays.copyOf(cur.a, cur.n);
                    Arrays.sort(its);
                    int u = 0;
                    for (int i = 0; i < its.length; i++) if (i == 0 || its[i] != its[i - 1]) its[u++] = its[i];
                    seq.add(Arrays.copyOf(its, u));
                    cur.n = 0;
                }
            } else cur.add(v);
        }
        if (seq.isEmpty()) return null;
        return seq.toArray(new int[0][]);
    }

    private static void readBatch(BufferedReader br, int[] line, int end, List<int[][]> seqs, IntList pos) throws IOException {
        String s;
        while (line[0] < end && (s = br.readLine()) != null) {
            if (!s.isEmpty() && s.charAt(0) != '@') {
                int[][] q = parse(s);
                if (q != null && containsTarget(q)) { seqs.add(q); pos.add(line[0] + 1); }
            }
            line[0]++;
        }
    }

    private void writeReport(Engine eng, int numBatches, double[] time, double[] mem, int[] pats, long[] resc,
                             int[] live, int[] tomb, int validRuns) {
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(OUTPUT_FILE), StandardCharsets.UTF_8))) {
            List<String> out = new ArrayList<>();
            List<Integer> sups = new ArrayList<>();
            eng.collect(out, sups);
            bw.write("PATTERNS AT THE LAST BATCH:\n");
            for (int i = 0; i < out.size(); i++) bw.write(out.get(i) + " : sup = " + sups.get(i) + "\n");
            bw.write("\n======================================================\n");
            bw.write("- Algorithm: IncAdaTaSPM\n");
            bw.write("- Dataset: " + AppConfig.DATASET_PATH + "\n");
            bw.write("- Original sequences: " + fullTotalTransCount + " | after target filter: " + validTotalDBSize + "\n");
            bw.write("- min_sup_rate=" + AppConfig.MIN_SUP_RATE + ", max_per=" + AppConfig.MAX_PER + ", eta=" + AppConfig.ETA
                    + ", lambda=" + AppConfig.LAMBDA + ", weights(V,R,S)=[" + AppConfig.W1 + ", " + AppConfig.W3 + ", " + AppConfig.W4 + "]\n");
            bw.write("- Valid measured runs: " + validRuns + "\n");
            bw.write("- Batch | cumulative time (s) | peak RAM (MB) | patterns | cumulative rescans | live nodes | tombstones\n");
            for (int b = 0; b < numBatches; b++)
                bw.write(String.format(Locale.US, "  %d | %.3f | %.2f | %d | %d | %d | %d%n",
                        b + 1, time[b] / validRuns / 1000.0, mem[b], pats[b], resc[b], live[b], tomb[b]));
        } catch (IOException e) { e.printStackTrace(); }
    }

    public static void main(String[] args) { new IncAdaTaSPM().run(); }
}
