// SPDX-License-Identifier: MIT
package com.adaptive.itr.spm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>PreLargeSeqTaSPM</b> ("Pre-large Seq.") - pattern-level pre-large baseline: the support form
 * of Pre-HUSPM (Yan, Li, Hsieh, Wu, The Journal of Supercomputing 81:81, 2025), that is, the
 * pre-large sequential method of Hong, Wang and Tseng (2011) with a projection-based miner and
 * the SWUmax rescan test.
 *
 * <p><b>Reduction.</b> Pre-HUSPM keeps the patterns whose sequence-weighted utility SWU (sum of
 * the utilities of the sequences that contain the pattern) is at least Sl * TSU, where TSU is
 * the total utility of the database. With the utility of every sequence set to 1, SWU is the
 * support and TSU is the number of sequences N, so:
 * <ul>
 *   <li>buffer (large and pre-large patterns): every pattern with support >= Sl * N;</li>
 *   <li>safety value f = (Su - Sl) / (1 - Su) * N at the last rescan, the formula of Hong et al.
 *       for insertions of new sequences only;</li>
 *   <li>SWUmax of a new batch d: the largest support in d of a pattern, reached by one item
 *       because support does not increase under extension.</li>
 * </ul>
 *
 * <p><b>Deviations from Pre-HUSPM, all forced by the problem.</b>
 * <ul>
 *   <li>The utility layer is dropped (the high-utility test of Algorithm 1 lines 24-27 and the
 *       projected-database bound on SWU); the output is every pattern with support >= min_sup.</li>
 *   <li>Supports, occurrence positions and gaps are taken over D_T (the sequences that contain
 *       a target item); the thresholds use N, which counts every sequence. SWUmax is computed
 *       over the target sequences of d; it bounds the gain in D_T of every pattern and is at
 *       most |d|, which is what the safety argument needs.</li>
 *   <li>Regularity: each buffered pattern carries its last occurrence position and its largest
 *       gap so far, updated by its new occurrences; the output is filtered to the patterns that
 *       contain a target item and are regular. In the benchmark arm the buffer also drops
 *       irregular patterns, as Re-mine and the batch baseline prune them ({@link #pruneIrregular});
 *       Pre-HUSPM keeps its buffer by support only, which the option false reproduces.</li>
 * </ul>
 *
 * <p><b>Procedure</b> (Algorithms 1 to 3 of Pre-HUSPM in support form). Su = min_sup_rate,
 * Sl = lowerRatio * Su.
 * <ul>
 *   <li>Initial mine and rescan (Algorithm 3): every pattern with support >= Sl * N in the whole
 *       current D_T is mined by pattern growth over projected databases and stored in a prefix
 *       tree; f is computed from the current N and buf is set to 0. The miner grows patterns
 *       like {@link BatchTaSPM} (the same projected sequences, S-extensions from the first end,
 *       I-extensions from every end) with the lower threshold and without regularity pruning.</li>
 *   <li>For a new batch with q = SWUmax: when (buf + q) > f the database is rescanned
 *       (Algorithm 1 lines 20-22); otherwise the safe update runs and buf += q.</li>
 *   <li>Safe update (Algorithm 1 lines 6-18 and Algorithm 2): the batch is projected along the
 *       buffered patterns, from their one-item patterns down; every buffered pattern gets its
 *       support and positions in the batch added and is dropped, with its extensions, when its
 *       support falls below Sl * N. A pattern that is not buffered is never added.</li>
 * </ul>
 * The test buf + q > f is evaluated exactly as (buf + q) * (1 - Su) > (Su - Sl) * N_rescan.
 *
 * <p><b>Counters.</b> {@link #rescans}: whole-database rescans after the initial mine.
 * {@link #projections}: projected lists built (mining and safe updates). {@link #liveNodes}:
 * buffered patterns after the last call.
 */
public final class PreLargeSeqTaSPM {

    // ------------------------------------------------------------------ parameters
    final BigDecimal su, sl;
    final int maxPer;
    final Set<Integer> targets;
    /** Drops irregular patterns from the buffer (mining and safe update). Exact: a pattern that is
     *  irregular stays irregular as the stream grows, and so do its extensions. On in the benchmark
     *  arm (the stronger baseline); Pre-HUSPM itself keeps the buffer by support only. */
    final boolean pruneIrregular;

    /** Output patterns are put here in canonical form "a b|c" with their support when set. */
    Map<String, Integer> sink = null;

    // ------------------------------------------------------------------ counters
    long rescans = 0, projections = 0;
    int liveNodes = 0;

    // ------------------------------------------------------------------ safety state
    private long nAtRescan = 0;   // N at the last rescan
    private long nNow = 0;        // N of the batch being processed
    private long buf = 0;         // q summed over the safe updates since the last rescan
    private boolean initialised = false;

    // ------------------------------------------------------------------ prefix tree
    // Node 0 is the root (empty pattern). kind: 1 = I-extension, 2 = S-extension.
    private int[] item = new int[1 << 12], sup = new int[1 << 12], last = new int[1 << 12], gap = new int[1 << 12];
    private int[] firstChild = new int[1 << 12], next = new int[1 << 12];
    private byte[] kind = new byte[1 << 12], hasT = new byte[1 << 12];
    private int used = 1, freeHead = -1;

    public PreLargeSeqTaSPM(BigDecimal minSupRate, double lowerRatio, int maxPer, Set<Integer> targets) {
        this(minSupRate, lowerRatio, maxPer, targets, false);
    }

    public PreLargeSeqTaSPM(BigDecimal minSupRate, double lowerRatio, int maxPer, Set<Integer> targets, boolean pruneIrregular) {
        this.pruneIrregular = pruneIrregular;
        this.su = minSupRate;
        this.sl = minSupRate.multiply(new BigDecimal(Double.toString(lowerRatio)));
        this.maxPer = maxPer;
        this.targets = targets;
        firstChild[0] = -1; next[0] = -1;
    }

    /** Lower threshold as a count: the smallest support s with s >= Sl * n, at least 1. */
    int lowSup(long n) {
        BigDecimal v = sl.multiply(BigDecimal.valueOf(n)).setScale(0, RoundingMode.CEILING);
        return Math.max(1, v.intValueExact());
    }

    /** Output threshold as a count, as {@link AppConfig#minSup}. */
    int minSup(long n) {
        BigDecimal v = su.multiply(BigDecimal.valueOf(n)).setScale(0, RoundingMode.CEILING);
        return Math.max(1, v.intValueExact());
    }

    /** True when (buf + q) > f, with f = (Su - Sl) / (1 - Su) * N_rescan, evaluated exactly. */
    boolean exceedsSafety(long q) {
        BigDecimal lhs = BigDecimal.valueOf(buf + q).multiply(BigDecimal.ONE.subtract(su));
        BigDecimal rhs = su.subtract(sl).multiply(BigDecimal.valueOf(nAtRescan));
        return lhs.compareTo(rhs) > 0;
    }

    /**
     * Processes one batch.
     *
     * @param all      every target sequence received so far (the batch included), file order
     * @param allLines 0-based file line of each entry of all
     * @param from     index in all of the first sequence of this batch
     * @param n        sequences received so far (every non-empty line)
     * @return number of output patterns
     */
    public long addBatch(List<int[][]> all, List<Integer> allLines, int from, long n) {
        nNow = n;
        if (!initialised) {
            rescan(all, allLines, n);
            initialised = true;
        } else {
            long q = swuMax(all, from);
            if (exceedsSafety(q)) {
                rescan(all, allLines, n);
                rescans++;
            } else {
                safeUpdate(all, allLines, from, n);
                buf += q;
            }
        }
        return output(n);
    }

    /** Largest number of target sequences of the batch that hold one item. */
    static long swuMax(List<int[][]> all, int from) {
        Map<Integer, Integer> c = new HashMap<>();
        Set<Integer> seen = new HashSet<>();
        int best = 0;
        for (int s = from; s < all.size(); s++) {
            seen.clear();
            for (int[] is : all.get(s)) for (int x : is) if (seen.add(x)) {
                int v = c.getOrDefault(x, 0) + 1;
                c.put(x, v);
                if (v > best) best = v;
            }
        }
        return best;
    }

    // ================================================================== projected sequences
    /** Ends of the pattern in one sequence: itemset indices tids (increasing) and the index,
     *  inside each, of the pattern's last item. As in {@link BatchTaSPM}. */
    static final class PSeq {
        final int sid;
        final int[] tids, offs;
        PSeq(int sid, int[] tids, int[] offs) { this.sid = sid; this.tids = tids; this.offs = offs; }
    }

    static final class EndList {
        final int sid;
        int[] t = new int[4], o = new int[4];
        int n = 0;
        EndList(int sid) { this.sid = sid; }
        void add(int tid, int off) {
            if (n == t.length) { t = Arrays.copyOf(t, 2 * n); o = Arrays.copyOf(o, 2 * n); }
            t[n] = tid; o[n] = off; n++;
        }
        PSeq done() { return new PSeq(sid, Arrays.copyOf(t, n), Arrays.copyOf(o, n)); }
    }

    /** Projections of one-item patterns onto all.get(from..): item -> list in sid order. Only
     *  the items accepted by want (null = every item) are projected. */
    private Map<Integer, List<PSeq>> projectItems(List<int[][]> all, int from, Set<Integer> want) {
        Map<Integer, List<PSeq>> m = new HashMap<>();
        Map<Integer, EndList> ends = new HashMap<>();
        for (int s = from; s < all.size(); s++) {
            int[][] seq = all.get(s);
            ends.clear();
            for (int i = 0; i < seq.length; i++) {
                for (int j = 0; j < seq[i].length; j++) {
                    int x = seq[i][j];
                    if (want != null && !want.contains(x)) continue;
                    EndList el = ends.get(x);
                    if (el == null) { el = new EndList(s); ends.put(x, el); }
                    el.add(i, j);
                }
            }
            for (Map.Entry<Integer, EndList> e : ends.entrySet()) m.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue().done());
        }
        projections += m.size();
        return m;
    }

    /** S- and I-extension projections of a pattern whose projected list is pdb. Only the items
     *  in sWant / iWant are projected (null = every item). Lists are in sid order. */
    private void extend(List<int[][]> all, List<PSeq> pdb, Set<Integer> sWant, Set<Integer> iWant,
                        Map<Integer, List<PSeq>> sMap, Map<Integer, List<PSeq>> iMap) {
        Map<Integer, EndList> sEnds = new HashMap<>(), iEnds = new HashMap<>();
        for (PSeq p : pdb) {
            int[][] seq = all.get(p.sid);
            sEnds.clear();
            for (int i = p.tids[0] + 1; i < seq.length; i++) {
                for (int j = 0; j < seq[i].length; j++) {
                    int x = seq[i][j];
                    if (sWant != null && !sWant.contains(x)) continue;
                    EndList el = sEnds.get(x);
                    if (el == null) { el = new EndList(p.sid); sEnds.put(x, el); }
                    el.add(i, j);
                }
            }
            for (Map.Entry<Integer, EndList> e : sEnds.entrySet()) sMap.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue().done());
            iEnds.clear();
            for (int k = 0; k < p.tids.length; k++) {
                int[] is = seq[p.tids[k]];
                for (int j = p.offs[k] + 1; j < is.length; j++) {
                    int x = is[j];
                    if (iWant != null && !iWant.contains(x)) continue;
                    EndList el = iEnds.get(x);
                    if (el == null) { el = new EndList(p.sid); iEnds.put(x, el); }
                    el.add(p.tids[k], j);
                }
            }
            for (Map.Entry<Integer, EndList> e : iEnds.entrySet()) iMap.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue().done());
        }
        projections += sMap.size() + iMap.size();
    }

    // ================================================================== rescan (Algorithm 3)
    private void rescan(List<int[][]> all, List<Integer> lines, long n) {
        clearTree();
        int low = lowSup(n);
        Map<Integer, List<PSeq>> roots = projectItems(all, 0, null);
        List<Integer> items = new ArrayList<>(roots.keySet());
        java.util.Collections.sort(items);
        for (int x : items) {
            List<PSeq> l = roots.get(x);
            if (l.size() < low) continue;
            int v = newNode(0, x, (byte) 2, l, lines);
            if (pruneIrregular && irregular(v)) { dropFirstChild(0); continue; }
            mine(all, lines, v, l, low);
        }
        nAtRescan = n;
        buf = 0;
    }

    private void mine(List<int[][]> all, List<Integer> lines, int v, List<PSeq> pdb, int low) {
        Map<Integer, List<PSeq>> sMap = new HashMap<>(), iMap = new HashMap<>();
        extend(all, pdb, null, null, sMap, iMap);
        for (Map.Entry<Integer, List<PSeq>> e : iMap.entrySet()) {
            if (e.getValue().size() < low) continue;
            int c = newNode(v, e.getKey(), (byte) 1, e.getValue(), lines);
            if (pruneIrregular && irregular(c)) { dropFirstChild(v); continue; }
            mine(all, lines, c, e.getValue(), low);
        }
        for (Map.Entry<Integer, List<PSeq>> e : sMap.entrySet()) {
            if (e.getValue().size() < low) continue;
            int c = newNode(v, e.getKey(), (byte) 2, e.getValue(), lines);
            if (pruneIrregular && irregular(c)) { dropFirstChild(v); continue; }
            mine(all, lines, c, e.getValue(), low);
        }
    }

    // ================================================================== safe update (Algorithms 1, 2)
    private void safeUpdate(List<int[][]> all, List<Integer> lines, int from, long n) {
        int low = lowSup(n);
        Set<Integer> want = new HashSet<>();
        for (int c = firstChild[0]; c >= 0; c = next[c]) want.add(item[c]);
        Map<Integer, List<PSeq>> roots = projectItems(all, from, want);
        updateChildren(all, lines, 0, roots, null, low);
    }

    /** Adds the batch occurrences to every child of v and recurses; drops a child (and its
     *  subtree) whose support falls below low. sMap / iMap: projections of v's S- and
     *  I-extensions onto the batch (null when v does not occur in the batch). For the root,
     *  sMap holds the one-item projections. */
    private void updateChildren(List<int[][]> all, List<Integer> lines, int v,
                                Map<Integer, List<PSeq>> sMap, Map<Integer, List<PSeq>> iMap, int low) {
        int prev = -1;
        for (int c = firstChild[v]; c >= 0; ) {
            int nx = next[c];
            List<PSeq> l = null;
            if (kind[c] == 2) { if (sMap != null) l = sMap.get(item[c]); }
            else if (iMap != null) l = iMap.get(item[c]);
            if (l != null) addOccurrences(c, l, lines);
            if (sup[c] < low || (pruneIrregular && irregular(c))) {
                if (prev < 0) firstChild[v] = nx; else next[prev] = nx;
                freeSubtree(c);
            } else {
                Map<Integer, List<PSeq>> cs = null, ci = null;
                if (l != null && firstChild[c] >= 0) {
                    Set<Integer> sw = new HashSet<>(), iw = new HashSet<>();
                    for (int g = firstChild[c]; g >= 0; g = next[g]) (kind[g] == 2 ? sw : iw).add(item[g]);
                    cs = new HashMap<>(); ci = new HashMap<>();
                    extend(all, l, sw, iw, cs, ci);
                }
                updateChildren(all, lines, c, cs, ci, low);
                prev = c;
            }
            c = nx;
        }
    }

    private void addOccurrences(int c, List<PSeq> l, List<Integer> lines) {
        for (PSeq p : l) {
            int ln = lines.get(p.sid);
            int g = ln - last[c];
            if (g > gap[c]) gap[c] = g;
            last[c] = ln;
        }
        sup[c] += l.size();
    }

    // ================================================================== output
    private long output(long n) {
        int ms = minSup(n);
        long cnt = 0;
        int[] stack = new int[64];
        int sp = 0;
        StringBuilder key = sink != null ? new StringBuilder() : null;
        if (sink != null) { sink.clear(); return collect(0, ms, n, key); }
        for (int c = firstChild[0]; c >= 0; c = next[c]) { if (sp == stack.length) stack = Arrays.copyOf(stack, 2 * sp); stack[sp++] = c; }
        int live = 0;
        while (sp > 0) {
            int v = stack[--sp];
            live++;
            if (sup[v] >= ms && hasT[v] != 0 && Math.max(gap[v], n - last[v]) <= maxPer) cnt++;
            for (int c = firstChild[v]; c >= 0; c = next[c]) { if (sp == stack.length) stack = Arrays.copyOf(stack, 2 * sp); stack[sp++] = c; }
        }
        liveNodes = live;
        return cnt;
    }

    private long collect(int v, int ms, long n, StringBuilder key) {
        long cnt = 0;
        int live = 0;
        for (int c = firstChild[v]; c >= 0; c = next[c]) {
            int len = key.length();
            if (v != 0) key.append(kind[c] == 1 ? ' ' : '|');
            key.append(item[c]);
            if (sup[c] >= ms && hasT[c] != 0 && Math.max(gap[c], n - last[c]) <= maxPer) { cnt++; sink.put(key.toString(), sup[c]); }
            cnt += collect(c, ms, n, key);
            key.setLength(len);
        }
        if (v == 0) { int[] s = {0}; countLive(0, s); liveNodes = s[0]; }
        return cnt;
    }

    private void countLive(int v, int[] s) { for (int c = firstChild[v]; c >= 0; c = next[c]) { s[0]++; countLive(c, s); } }

    // ================================================================== tree storage
    private int newNode(int parent, int x, byte k, List<PSeq> l, List<Integer> lines) {
        int v;
        if (freeHead >= 0) { v = freeHead; freeHead = next[v]; }
        else {
            if (used == item.length) grow();
            v = used++;
        }
        item[v] = x; kind[v] = k; firstChild[v] = -1;
        hasT[v] = (byte) ((parent != 0 && hasT[parent] != 0) || targets.contains(x) ? 1 : 0);
        int lastLn = -1, g = 0;
        for (PSeq p : l) {
            int ln = lines.get(p.sid);
            g = Math.max(g, lastLn < 0 ? ln + 1 : ln - lastLn);
            lastLn = ln;
        }
        sup[v] = l.size(); last[v] = lastLn; gap[v] = g;
        next[v] = firstChild[parent];
        firstChild[parent] = v;
        return v;
    }

    private void grow() {
        int m = item.length * 2;
        item = Arrays.copyOf(item, m); sup = Arrays.copyOf(sup, m); last = Arrays.copyOf(last, m); gap = Arrays.copyOf(gap, m);
        firstChild = Arrays.copyOf(firstChild, m); next = Arrays.copyOf(next, m);
        kind = Arrays.copyOf(kind, m); hasT = Arrays.copyOf(hasT, m);
    }

    private void freeSubtree(int v) {
        int[] stack = new int[64];
        int sp = 0;
        stack[sp++] = v;
        while (sp > 0) {
            int u = stack[--sp];
            for (int c = firstChild[u]; c >= 0; c = next[c]) { if (sp == stack.length) stack = Arrays.copyOf(stack, 2 * sp); stack[sp++] = c; }
            next[u] = freeHead;
            freeHead = u;
        }
    }

    private boolean irregular(int v) { return Math.max(gap[v], nNow - last[v]) > maxPer; }

    /** Removes the newest child of p (just created by newNode, so it has no children). */
    private void dropFirstChild(int p) {
        int v = firstChild[p];
        firstChild[p] = next[v];
        next[v] = freeHead;
        freeHead = v;
    }

    private void clearTree() {
        used = 1; freeHead = -1;
        firstChild[0] = -1; next[0] = -1;
    }
}
