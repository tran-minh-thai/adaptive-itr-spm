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

    static int key(int item, boolean sExt) { return item * 2 + (sExt ? 1 : 0); }

    /**
     * The prefix tree, stored as parallel arrays indexed by node id (no object per node).
     * Row arrays (children, tombstones, stubs) are exact-sized; a null row means empty.
     */
    static final class Tree {
        int cap = 1024, n = 1;          // id 0 is the root
        int[] key = new int[cap], sup = new int[cap], last = new int[cap], g = new int[cap], b = new int[cap];
        long[] sq = new long[cap];      // sum of squared gaps (gap statistics for Stab)
        byte[] fl = new byte[cap];      // bit 0 hasT, bit 1 resc, bit 2 promo, bit 3 marked in this batch
        int[][] kid = new int[cap][];   // live children ids, sorted by key
        int[][] dead = new int[cap][];  // tombstone keys (permanently irregular), sorted
        int[][] stub = new int[cap][];  // stubs: key0, sup0, key1, sup1, ... sorted by key
        int[] fr = new int[1024];
        int nfr = 0, live = 1;

        static final byte HAST = 1, RESC = 2, PROMO = 4, MARK = 8;

        Tree() { key[0] = -1; }

        void grow() {
            int c = cap + (cap >> 2) + 1024;
            key = Arrays.copyOf(key, c); sup = Arrays.copyOf(sup, c);
            last = Arrays.copyOf(last, c); g = Arrays.copyOf(g, c); b = Arrays.copyOf(b, c);
            sq = Arrays.copyOf(sq, c); fl = Arrays.copyOf(fl, c);
            kid = Arrays.copyOf(kid, c); dead = Arrays.copyOf(dead, c); stub = Arrays.copyOf(stub, c);
            cap = c;
        }

        int alloc(int parent, int k, boolean hasT) {
            int id;
            if (nfr > 0) id = fr[--nfr];
            else { if (n == cap) grow(); id = n++; }
            key[id] = k; sup[id] = 0; last[id] = 0; g[id] = 0; b[id] = 0;
            sq[id] = 0; fl[id] = hasT ? HAST : 0;
            kid[id] = null; dead[id] = null; stub[id] = null;
            live++;
            return id;
        }

        /** Releases a node and its whole subtree. */
        int[] stk = new int[64];
        void freeSubtree(int id) {
            int sp = 0;
            stk[sp++] = id;
            while (sp > 0) {
                int x = stk[--sp];
                int[] ks = kid[x];
                if (ks != null) {
                    if (sp + ks.length > stk.length) stk = Arrays.copyOf(stk, Math.max(stk.length * 2, sp + ks.length));
                    for (int c : ks) stk[sp++] = c;
                }
                kid[x] = null; dead[x] = null; stub[x] = null;
                if (nfr == fr.length) fr = Arrays.copyOf(fr, nfr * 2);
                fr[nfr++] = x;
                live--;
            }
        }

        void addPos(int id, int p) {
            int gap = p - last[id];
            if (gap > g[id]) g[id] = gap;
            int c = ++sup[id];
            sq[id] += (long) gap * gap;
            last[id] = p;
        }

        int findKid(int id, int k) {
            int[] r = kid[id];
            if (r == null) return -1;
            int lo = 0, hi = r.length - 1;
            while (lo <= hi) {
                int m = (lo + hi) >>> 1, v = key[r[m]];
                if (v < k) lo = m + 1; else if (v > k) hi = m - 1; else return m;
            }
            return -1;
        }

        boolean isDead(int id, int k) { int[] r = dead[id]; return r != null && Arrays.binarySearch(r, k) >= 0; }

        boolean hasKid(int id, int k) { return findKid(id, k) >= 0 || isDead(id, k); }

        int findStub(int id, int k) {
            int[] r = stub[id];
            if (r == null) return -1;
            int lo = 0, hi = (r.length >> 1) - 1;
            while (lo <= hi) {
                int m = (lo + hi) >>> 1, v = r[2 * m];
                if (v < k) lo = m + 1; else if (v > k) hi = m - 1; else return m;
            }
            return -1;
        }

        /** Merges unsorted keys (none present) into the tombstone row. */
        void addDead(int id, int[] keys, int nn) {
            Arrays.sort(keys, 0, nn);
            int[] d = dead[id];
            int nd = d == null ? 0 : d.length;
            int[] r = new int[nd + nn];
            int i = 0, j = 0, o = 0;
            while (i < nd || j < nn) {
                if (j >= nn || (i < nd && d[i] < keys[j])) r[o++] = d[i++]; else r[o++] = keys[j++];
            }
            dead[id] = r;
        }

        /** Merges packed (key << 32 | sup) entries (keys not present) into the stub row. */
        void addStubs(int id, long[] e, int nn) {
            Arrays.sort(e, 0, nn);
            int[] s = stub[id];
            int ns = s == null ? 0 : s.length >> 1;
            int[] r = new int[2 * (ns + nn)];
            int i = 0, j = 0, o = 0;
            while (i < ns || j < nn) {
                int kj = j < nn ? (int) (e[j] >>> 32) : 0;
                if (j >= nn || (i < ns && s[2 * i] < kj)) { r[o] = s[2 * i]; r[o + 1] = s[2 * i + 1]; i++; }
                else { r[o] = kj; r[o + 1] = (int) e[j]; j++; }
                o += 2;
            }
            stub[id] = r;
        }

        /** Merges new child ids (keys not present) into the child row. */
        void addKids(int id, int[] ids, int nn) {
            long[] ord = new long[nn];
            for (int i = 0; i < nn; i++) ord[i] = ((long) key[ids[i]] << 32) | (ids[i] & 0xffffffffL);
            Arrays.sort(ord);
            int[] s = kid[id];
            int ns = s == null ? 0 : s.length;
            int[] r = new int[ns + nn];
            int i = 0, j = 0, o = 0;
            while (i < ns || j < nn) {
                if (j >= nn || (i < ns && key[s[i]] < (int) (ord[j] >>> 32))) r[o++] = s[i++];
                else r[o++] = (int) ord[j++];
            }
            kid[id] = r;
        }
    }

    public static class Engine {
        final BigDecimal rate;
        final int maxPer;
        final Set<Integer> T;
        final double eta, lam;
        double wV, wR, wS;
        /** Weight of the target-proximity component (0 = component off, the default). */
        double wT = 0;
        /** Score fixed to 1 for every pattern: LB = (1 - eta) * min_sup (static pre-large arm). */
        boolean scoreConst = false;

        int[][][] db = new int[1024][][];
        int[] pos = new int[1024];
        int nDT = 0;
        long N = 0;

        IntList[] occSids = new IntList[0], occOffs = new IntList[0], occTids = new IntList[0];
        int maxItem = -1;

        final Tree t = new Tree();
        static final int ROOT = 0;
        boolean first = true;
        /** Cumulative counters: rescans (expand of a marked node on the full D_T), stub promotions,
         *  and projections computed (calls of itemProj and extend). */
        public long rescans = 0, promotions = 0, projections = 0;
        /** Rescans after which P has at least one tracked child it did not have before. */
        public long rescanInserts = 0;
        /** Stub floor as a fraction of min_sup: untracked children at or above it keep an exact count. */
        double stubFrac = Double.parseDouble(System.getProperty("inc.stubfrac", "0.5"));
        /** Lazy insertion: a new child becomes a tracked node only once its support reaches min_sup;
         *  below that it is a stub (or folds into b). LB then acts only as the demotion threshold. */
        boolean lazy = Boolean.parseBoolean(System.getProperty("inc.lazy", "true"));
        /** Per sequence of D_T: distance (in itemsets) from each itemset to the nearest itemset
         *  holding a target item; filled only when wT > 0. */
        int[][] tdist = new int[0][];

        public Engine stubFrac(double v) { if (!(v > 0 && v <= 1)) throw new IllegalArgumentException("phi must be in (0,1]"); stubFrac = v; return this; }
        public Engine lazy(boolean v) { lazy = v; return this; }
        public Engine scoreConst(boolean v) { scoreConst = v; return this; }
        public Engine targetProximityWeight(double v) { if (v < 0) throw new IllegalArgumentException("w_tbr must be >= 0"); wT = v; return this; }
        public double stubFrac() { return stubFrac; }
        public boolean lazy() { return lazy; }
        int stubFloor;
        int nDold, nDnew, deltaStart, curMinSup, batchId = 0;
        long posEnd;

        // local counting buffers
        int[] cntI = new int[0], cntS = new int[0], seenI = new int[0], seenS = new int[0];
        int[] trI = new int[0], trS = new int[0];
        boolean[] isT = new boolean[0];
        // projection scratch buffers
        int[] tSids = new int[1024], tOffs = new int[1025], tTids = new int[4096];

        public Engine(BigDecimal rate, int maxPer, Set<Integer> T, double eta, double lam,
                      double wV, double wR, double wS) {
            if (!(eta >= 0 && eta < 1)) throw new IllegalArgumentException("eta must be in [0,1)");
            this.rate = rate; this.maxPer = maxPer; this.T = T; this.eta = eta; this.lam = lam;
            if (wV + wR + wS <= 0) { wV = 1; wR = 1; wS = 1; }
            this.wV = wV; this.wR = wR; this.wS = wS;
        }

        public int minSup() { return curMinSup; }
        long tWalk, tKP, tResc;
        public String prof() { String r = String.format(java.util.Locale.US, "walk=%.0f resc=%.0f", tWalk/1e6, tResc/1e6); tWalk=tKP=tResc=0; return r; }

        int minSupOf(long n) {
            BigDecimal v = rate.multiply(BigDecimal.valueOf(n)).setScale(0, RoundingMode.CEILING);
            return Math.max(1, v.intValueExact());
        }

        // ---------------------------------------------------------------- storage
        void ensureItem(int x) {
            if (x <= maxItem) return;
            int cap = Math.max(x + 1, occSids.length * 2);
            occSids = Arrays.copyOf(occSids, cap); occOffs = Arrays.copyOf(occOffs, cap); occTids = Arrays.copyOf(occTids, cap);
            cntI = Arrays.copyOf(cntI, cap); cntS = Arrays.copyOf(cntS, cap);
            int old = seenI.length;
            seenI = Arrays.copyOf(seenI, cap); seenS = Arrays.copyOf(seenS, cap);
            for (int i = old; i < cap; i++) { seenI[i] = -1; seenS[i] = -1; }
            trI = Arrays.copyOf(trI, cap); trS = Arrays.copyOf(trS, cap);
            isT = Arrays.copyOf(isT, cap);
            for (int i = maxItem + 1; i < cap; i++) isT[i] = T.contains(i);
            maxItem = cap - 1;
        }

        void appendSeq(int[][] seq, int position) {
            if (nDT == db.length) { db = Arrays.copyOf(db, nDT * 2); pos = Arrays.copyOf(pos, nDT * 2); }
            int sid = nDT++;
            db[sid] = seq; pos[sid] = position;
            if (wT > 0) {
                if (sid >= tdist.length) tdist = Arrays.copyOf(tdist, Math.max(1024, tdist.length * 2));
                tdist[sid] = targetDistances(seq);
            }
            for (int tt = 0; tt < seq.length; tt++) {
                for (int x : seq[tt]) {
                    ensureItem(x);
                    if (occSids[x] == null) { occSids[x] = new IntList(); occOffs[x] = new IntList(); occTids[x] = new IntList(); }
                    IntList s = occSids[x];
                    if (s.n == 0 || s.a[s.n - 1] != sid) { s.add(sid); occOffs[x].add(occTids[x].n); }
                    IntList tl = occTids[x];
                    if (tl.n == 0 || occOffs[x].a[occOffs[x].n - 1] == tl.n || tl.a[tl.n - 1] != tt) tl.add(tt);
                }
            }
        }

        /** d[i] = min over itemsets j holding a target item of |i - j| (two linear passes). */
        int[] targetDistances(int[][] seq) {
            int n = seq.length, inf = Integer.MAX_VALUE / 2;
            int[] d = new int[n];
            int lastT = -inf;
            for (int i = 0; i < n; i++) {
                for (int x : seq[i]) if (T.contains(x)) { lastT = i; break; }
                d[i] = i - lastT;
            }
            lastT = inf;
            for (int i = n - 1; i >= 0; i--) {
                if (d[i] == 0) lastT = i;
                d[i] = Math.min(d[i], lastT - i);
            }
            return d;
        }

        /** Target proximity of one occurrence: 1 - dmin / (len - 1) over the itemsets where the
         *  pattern's last itemset is matched in sequence sid (1 for a one-itemset sequence). */
        double proximity(Proj cp, int q) {
            int sid = cp.sids[q];
            int[] d = tdist[sid];
            if (d.length <= 1) return 1.0;
            int dmin = Integer.MAX_VALUE;
            for (int k = cp.offs[q]; k < cp.offs[q + 1]; k++) dmin = Math.min(dmin, d[cp.tids[k]]);
            return 1.0 - (double) dmin / (d.length - 1);
        }

        /** Projection of the 1-item pattern x restricted to sids >= fromSid. */
        Proj itemProj(int x, int fromSid) {
            projections++;
            IntList s = occSids[x], o = occOffs[x], tl = occTids[x];
            int lo = lowerBound(s.a, 0, s.n, fromSid);
            Proj p = new Proj();
            p.size = s.n - lo;
            p.sids = Arrays.copyOfRange(s.a, lo, s.n);
            p.offs = new int[p.size + 1];
            int base = p.size > 0 ? o.a[lo] : tl.n;
            for (int i = 0; i < p.size; i++) p.offs[i] = o.a[lo + i] - base;
            p.offs[p.size] = tl.n - base;
            p.tids = Arrays.copyOfRange(tl.a, base, tl.n);
            return p;
        }

        static int lowerBound(int[] a, int lo, int hi, int v) {
            while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] < v) lo = m + 1; else hi = m; }
            return lo;
        }

        /** First index j >= from with a[j] >= v (galloping search), or hi. */
        static int gallop(int[] a, int from, int hi, int v) {
            if (from >= hi || a[from] >= v) return from;
            int step = 1, lo = from;
            while (lo + step < hi && a[lo + step] < v) { lo += step; step <<= 1; }
            return lowerBound(a, lo + 1, Math.min(hi, lo + step + 1), v);
        }

        /** Projection of the extension of p by item x, using x's occurrence lists. With
         *  checkReg, returns null as soon as the extension is known to be irregular. */
        Proj extend(Proj p, int x, boolean sExt, boolean checkReg) {
            projections++;
            IntList os = occSids[x], oo = occOffs[x], ot = occTids[x];
            int[] xs = os.a, xo = oo.a, xt = ot.a;
            int xn = os.n, xtn = ot.n;
            int ns = 0, nt = 0, j = 0, prev = 0;
            for (int i = 0; i < p.size && j < xn; i++) {
                int sid = p.sids[i];
                j = gallop(xs, j, xn, sid);
                if (j >= xn) break;
                if (xs[j] != sid) continue;
                int a0 = xo[j], a1 = (j + 1 < xn) ? xo[j + 1] : xtn;
                int start = nt;
                if (sExt) {
                    int minP = p.tids[p.offs[i]];
                    int k = lowerBound(xt, a0, a1, minP + 1);
                    int need = nt + (a1 - k);
                    if (need > tTids.length) tTids = Arrays.copyOf(tTids, Math.max(need, tTids.length * 2));
                    System.arraycopy(xt, k, tTids, nt, a1 - k); nt = need;
                } else {
                    int k = a0;
                    for (int q = p.offs[i]; q < p.offs[i + 1] && k < a1; q++) {
                        int tq = p.tids[q];
                        while (k < a1 && xt[k] < tq) k++;
                        if (k < a1 && xt[k] == tq) {
                            if (nt == tTids.length) tTids = Arrays.copyOf(tTids, nt * 2);
                            tTids[nt++] = tq;
                        }
                    }
                }
                if (nt > start) {
                    if (checkReg) {
                        int ps = pos[sid];
                        if (ps - prev > maxPer) return null;
                        prev = ps;
                    }
                    if (ns + 1 >= tSids.length) { int nl = tSids.length * 2; tSids = Arrays.copyOf(tSids, nl); tOffs = Arrays.copyOf(tOffs, nl + 1); }
                    tSids[ns] = sid; tOffs[ns] = start; ns++;
                }
                j++;
            }
            if (checkReg && (ns == 0 || posEnd - prev > maxPer)) return null;
            Proj c = new Proj();
            c.size = ns;
            c.sids = Arrays.copyOf(tSids, ns);
            c.offs = Arrays.copyOf(tOffs, ns + 1);
            c.offs[ns] = nt;
            c.tids = Arrays.copyOf(tTids, nt);
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
                for (int tt = minP + 1; tt < seq.length; tt++) {
                    for (int x : seq[tt]) {
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
        /** Score(P) in [0,1]. supDelta, rsum, tsum and rcnt describe P's occurrences in the
         *  batch: their number, the sum of exp(-lambda * (N + 1 - position)), the sum of target
         *  proximities, and the count used to average the last two (0 outside a batch walk). */
        double score(int id, int supDelta, double rsum, double tsum, int rcnt) {
            if (scoreConst) return 1.0;
            int old = t.sup[id] - supDelta;
            double V;
            if (first || old == 0 || nDold == 0 || nDnew == 0) V = 1.0;
            else V = Math.min(1.0, ((double) supDelta / nDnew) / ((double) old / nDold));
            double R = rcnt > 0 ? rsum / rcnt : 0.0;
            double S;
            int wc = t.sup[id];
            // gaps sum to last, so mean = last / sup and variance = sumsq / sup - mean^2
            double mean = (double) t.last[id] / wc;
            if (wc > 1 && mean > 0) {
                double sd = Math.sqrt(Math.max(0.0, (double) t.sq[id] / wc - mean * mean));
                S = 1.0 - Math.min(1.0, sd / mean);
            } else S = 0.0;
            if (wT > 0) {
                double TP = rcnt > 0 ? tsum / rcnt : 0.0;
                return (wV * V + wT * TP + wR * R + wS * S) / (wV + wT + wR + wS);
            }
            return (wV * V + wR * R + wS * S) / (wV + wR + wS);
        }

        double LB(int id, int supDelta, double rsum, double tsum, int rcnt) {
            return curMinSup * (1 - eta * score(id, supDelta, rsum, tsum, rcnt));
        }

        boolean irregular(int id) {
            return t.sup[id] == 0 || Math.max(t.g[id], (long) (N + 1 - t.last[id])) > maxPer;
        }

        int newNode(int parent, int item, boolean sExt, Proj p) {
            int c = t.alloc(parent, key(item, sExt), (t.fl[parent] & Tree.HAST) != 0 || isT[item]);
            for (int i = 0; i < p.size; i++) t.addPos(c, pos[p.sids[i]]);
            return c;
        }

        // growable scratch lists used by expand/promote/walk (one frame at a time, copied out)
        static final class Acc {
            int nn, nd, ns;
            int[] add = new int[8]; Proj[] addP = new Proj[8]; int[] dead = new int[8]; long[] stub = new long[8];
            void kid(int c, Proj p) { if (nn == add.length) { add = Arrays.copyOf(add, nn * 2); addP = Arrays.copyOf(addP, nn * 2); } add[nn] = c; addP[nn] = p; nn++; }
            void dead(int k) { if (nd == dead.length) dead = Arrays.copyOf(dead, nd * 2); dead[nd++] = k; }
            void stub(int k, int s) { if (ns == stub.length) stub = Arrays.copyOf(stub, ns * 2); stub[ns++] = ((long) k << 32) | s; }
            Acc reset() { nn = 0; nd = 0; ns = 0; return this; }
        }
        Acc[] pool = new Acc[64];
        Acc acc(int d) {
            if (d >= pool.length) pool = Arrays.copyOf(pool, d * 2);
            Acc a = pool[d];
            if (a == null) pool[d] = a = new Acc();
            return a.reset();
        }

        // ---------------------------------------------------------------- expansion
        /** Builds every missing child of P from its projection pp on the full D_T (or the root). */
        void expand(int P, Proj pp, int d) {
            double lbMin = lazy ? curMinSup : curMinSup * (1 - eta);
            if (P == ROOT) countRoot(0); else countChildren(pp, t.key[P] >> 1);
            int bmax = 0;
            Acc a = acc(d);
            t.stub[P] = null;   // stubs are rebuilt from the exact counts
            int mI = nI, mT = nI + nS;
            for (int i = 0; i < mT; i++) {
                boolean sx = i >= mI;
                int item = sx ? trS[i - mI] : trI[i];
                int cnt = sx ? cntS[item] : cntI[item];
                int k = key(item, sx);
                if (t.hasKid(P, k)) continue;
                if (cnt < lbMin) {
                    if (cnt >= stubFloor) a.stub(k, cnt); else if (cnt > bmax) bmax = cnt;
                    continue;
                }
                Proj cp; int c = -1;
                if (P != ROOT && cnt >= curMinSup) {
                    // sup = cnt >= min_sup >= LB: only regularity decides, with early exit
                    cp = extend(pp, item, sx, true);
                    if (cp != null) c = newNode(P, item, sx, cp);
                } else {
                    cp = (P == ROOT) ? itemProj(item, 0) : extend(pp, item, sx, false);
                    c = newNode(P, item, sx, cp);
                    if (t.sup[c] < LB(c, first ? t.sup[c] : 0, 0.0, 0.0, 0)) {
                        int s = t.sup[c];
                        t.freeSubtree(c);
                        if (s >= stubFloor) a.stub(k, s); else if (s > bmax) bmax = s;
                        continue;
                    }
                    if (irregular(c)) { t.freeSubtree(c); cp = null; }
                }
                if (cp == null) { a.dead(k); continue; }
                a.kid(c, cp);
            }
            resetCounts();
            t.b[P] = bmax;
            if (a.ns > 0) t.addStubs(P, a.stub, a.ns);
            if (a.nd > 0) t.addDead(P, a.dead, a.nd);
            if (a.nn > 0) t.addKids(P, a.add, a.nn);
            for (int i = 0; i < a.nn; i++) { Proj cp = a.addP[i]; a.addP[i] = null; expand(a.add[i], cp, d + 1); }
        }

        /** Turns the stubs of P whose exact support reached min_sup into tracked nodes. */
        void promote(int P, Proj pp, int d) {
            int[] s = t.stub[P];
            if (s == null) return;
            Acc a = acc(d);
            int ns = s.length >> 1, o = 0;
            for (int i = 0; i < ns; i++) {
                int k = s[2 * i], sv = s[2 * i + 1];
                if (sv < curMinSup) { s[2 * o] = k; s[2 * o + 1] = sv; o++; continue; }
                promotions++;
                int item = k >>> 1; boolean sx = (k & 1) == 1;
                Proj cp; int c = -1;
                if (P == ROOT) { cp = itemProj(item, 0); c = newNode(P, item, sx, cp); if (irregular(c)) { t.freeSubtree(c); cp = null; } }
                else { cp = extend(pp, item, sx, true); if (cp != null) c = newNode(P, item, sx, cp); }
                if (cp == null) a.dead(k); else a.kid(c, cp);
            }
            t.stub[P] = o == 0 ? null : Arrays.copyOf(s, 2 * o);
            if (a.nd > 0) t.addDead(P, a.dead, a.nd);
            if (a.nn > 0) t.addKids(P, a.add, a.nn);
            for (int i = 0; i < a.nn; i++) { Proj cp = a.addP[i]; a.addP[i] = null; expand(a.add[i], cp, d + 1); }
        }

        // ---------------------------------------------------------------- one batch
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
            stubFloor = Math.max(2, (int) Math.ceil(stubFrac * curMinSup));

            if (first) {
                t.sup[ROOT] = nDT;
                expand(ROOT, null, 0);
                first = false;
                return;
            }
            t.sup[ROOT] += nDnew;

            long q0 = System.nanoTime();
            boolean m = walk(ROOT, null);
            long q1 = System.nanoTime();
            if (m) rescan(ROOT, null, 0);
            tWalk += q1 - q0; tResc += System.nanoTime() - q1;
        }

        /** Visits the live nodes marked in this batch, carrying their projections on the full D_T. */
        void rescan(int P, Proj pp, int d) {
            t.fl[P] &= ~Tree.MARK;
            int[] kids0 = t.kid[P];
            if ((t.fl[P] & Tree.RESC) != 0) {
                t.fl[P] &= ~(Tree.RESC | Tree.PROMO); rescans++;
                int before = kids0 == null ? 0 : kids0.length;
                expand(P, pp, d);
                if ((t.kid[P] == null ? 0 : t.kid[P].length) > before) rescanInserts++;
            }
            else if ((t.fl[P] & Tree.PROMO) != 0) { t.fl[P] &= ~Tree.PROMO; promote(P, pp, d); }
            // only children that existed before this expansion can carry marks
            if (kids0 == null) return;
            for (int c : kids0) {
                if ((t.fl[c] & Tree.MARK) == 0) continue;
                int item = t.key[c] >> 1; boolean sx = (t.key[c] & 1) == 1;
                Proj cp = (P == ROOT) ? itemProj(item, 0) : extend(pp, item, sx, false);
                rescan(c, cp, d + 1);
            }
        }

        void markUp(int P, byte flag) { t.fl[P] |= flag | Tree.MARK; }

        static final Proj EMPTY = new Proj();
        static { EMPTY.size = 0; EMPTY.sids = new int[0]; EMPTY.offs = new int[1]; EMPTY.tids = new int[0]; }

        boolean walkPromo;
        /** Counted child (key, batch count) seen by walk at P: updates a stub or the untracked maximum. */
        int stubOrMax(int P, int k, int cnt, int maxv) {
            if (t.findKid(P, k) >= 0 || t.isDead(P, k)) return maxv;
            int si = t.findStub(P, k);
            if (si >= 0) { int[] s = t.stub[P]; s[2 * si + 1] += cnt; if (s[2 * si + 1] >= curMinSup) walkPromo = true; return maxv; }
            return cnt > maxv ? cnt : maxv;
        }

        /** Steps 2 to 5 in one pass: updates every tracked child with the batch, kills irregular
         *  children (M2), demotes children below LB to stubs or into b, decides rescans. */
        /** Returns whether P or a node below it was marked for a rescan or a promotion. */
        boolean walk(int P, Proj pd) {
            int maxv = 0;
            walkPromo = false;
            if (P == ROOT) {
                countRoot(deltaStart);
                for (int i = 0; i < nI; i++) { int x = trI[i]; maxv = stubOrMax(P, key(x, false), cntI[x], maxv); }
                resetCounts();
            } else if (pd.size > 0) {
                countChildren(pd, t.key[P] >> 1);
                for (int i = 0; i < nI; i++) { int x = trI[i]; maxv = stubOrMax(P, key(x, false), cntI[x], maxv); }
                for (int i = 0; i < nS; i++) { int x = trS[i]; maxv = stubOrMax(P, key(x, true), cntS[x], maxv); }
                resetCounts();
            }
            if (maxv > 0) {
                if (t.b[P] + maxv >= curMinSup) markUp(P, Tree.RESC);
                else t.b[P] += maxv;
            }
            if (walkPromo && (t.fl[P] & Tree.RESC) == 0) markUp(P, Tree.PROMO);
            int[] s = t.stub[P];
            if (s != null && (t.fl[P] & (Tree.RESC | Tree.PROMO)) == 0) {
                // stubs that fell below the floor fold into b
                int ns = s.length >> 1, o = 0;
                for (int i = 0; i < ns; i++) {
                    if (s[2 * i + 1] >= stubFloor) { s[2 * o] = s[2 * i]; s[2 * o + 1] = s[2 * i + 1]; o++; }
                    else if (s[2 * i + 1] > t.b[P]) t.b[P] = s[2 * i + 1];
                }
                if (o < ns) t.stub[P] = o == 0 ? null : Arrays.copyOf(s, 2 * o);
            }
            int[] kids = t.kid[P];
            if (kids == null) return (t.fl[P] & Tree.MARK) != 0;
            Acc a = null;
            int o = 0;
            boolean sub = false;
            for (int i = 0; i < kids.length; i++) {
                int c = kids[i];
                int item = t.key[c] >> 1; boolean sx = (t.key[c] & 1) == 1;
                Proj cp;
                if (P == ROOT) cp = itemProj(item, deltaStart);
                else if (pd.size == 0) cp = EMPTY;
                else cp = extend(pd, item, sx, false);
                double rs = 0, ts = 0;
                for (int q = 0; q < cp.size; q++) {
                    int p = pos[cp.sids[q]];
                    t.addPos(c, p);
                    rs += Math.exp(-lam * (posEnd - p));
                    if (wT > 0) ts += proximity(cp, q);
                }
                if (irregular(c)) {
                    if (a == null) a = new Acc();
                    a.dead(t.key[c]);
                    t.freeSubtree(c);
                    continue;
                }
                if (t.sup[c] < LB(c, cp.size, rs, ts, cp.size)) {
                    int sc = t.sup[c];
                    if (sc >= stubFloor) { if (a == null) a = new Acc(); a.stub(t.key[c], sc); }
                    else if (sc > t.b[P]) t.b[P] = sc;
                    t.freeSubtree(c);
                    continue;
                }
                if (walk(c, cp)) sub = true;
                kids[o++] = c;
            }
            if (o < kids.length) t.kid[P] = o == 0 ? null : Arrays.copyOf(kids, o);
            if (a != null) {
                if (a.nd > 0) t.addDead(P, a.dead, a.nd);
                if (a.ns > 0) t.addStubs(P, a.stub, a.ns);
            }
            if (sub) t.fl[P] |= Tree.MARK;
            return (t.fl[P] & Tree.MARK) != 0;
        }

        // ---------------------------------------------------------------- output and counters
        public void collect(List<String> out, List<Integer> sups) { collect(ROOT, new ArrayList<int[]>(), out, sups); }

        void collect(int P, ArrayList<int[]> path, List<String> out, List<Integer> sups) {
            int[] kids = t.kid[P];
            if (kids == null) return;
            for (int c : kids) {
                path.add(new int[]{t.key[c] >> 1, t.key[c] & 1});
                if (t.sup[c] >= curMinSup && (t.fl[c] & Tree.HAST) != 0 && Math.max(t.g[c], N + 1 - t.last[c]) <= maxPer) {
                    out.add(patternString(path));
                    if (sups != null) sups.add(t.sup[c]);
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

        /** Number of output patterns (the nodes collect would emit), without building them. */
        public long countOutput() {
            long n = 0;
            int sp = 0;
            int[] stk = new int[64];
            stk[sp++] = ROOT;
            while (sp > 0) {
                int P = stk[--sp];
                int[] kids = t.kid[P];
                if (kids == null) continue;
                for (int c : kids) {
                    if (t.sup[c] >= curMinSup && (t.fl[c] & Tree.HAST) != 0 && Math.max(t.g[c], N + 1 - t.last[c]) <= maxPer) n++;
                    if (sp == stk.length) stk = Arrays.copyOf(stk, sp * 2);
                    stk[sp++] = c;
                }
            }
            return n;
        }

        /** {live nodes excluding the root, tombstones, stubs, |F| = live nodes with sup >= min_sup}. */
        public long[] treeCounts() { long[] r = new long[4]; count(ROOT, r); return r; }
        void count(int P, long[] r) {
            if (t.dead[P] != null) r[1] += t.dead[P].length;
            if (t.stub[P] != null) r[2] += t.stub[P].length >> 1;
            int[] kids = t.kid[P];
            if (kids == null) return;
            for (int c : kids) { r[0]++; if (t.sup[c] >= curMinSup) r[3]++; count(c, r); }
        }
    }

    // =====================================================================================
    // Benchmark driver (same batching, filtering and timing scope as RemineTaSPM)
    // =====================================================================================

    private int fullTotalTransCount, validTotalDBSize;
    private final String OUTPUT_FILE = "result_IncAdaTaSPM.txt";

    public void run() {
        AppConfig.load();
        precount(AppConfig.DATASET_PATH);
        int[] ends = AppConfig.batchEnds(fullTotalTransCount);
        int numBatches = ends.length;

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
            Engine eng = new Engine(AppConfig.MIN_SUP_RATE_EXACT,
                    AppConfig.MAX_PER, AppConfig.TARGET_ITEMS, AppConfig.ETA, AppConfig.LAMBDA,
                    AppConfig.W1, AppConfig.W3, AppConfig.W4)
                    .stubFrac(AppConfig.PHI).lazy(AppConfig.LAZY)
                    .scoreConst("const".equals(AppConfig.SCORE_MODE)).targetProximityWeight(AppConfig.W_TBR);
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
                        long[] tc = eng.treeCounts();
                        live[b] = (int) tc[0]; tomb[b] = (int) tc[1];
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
