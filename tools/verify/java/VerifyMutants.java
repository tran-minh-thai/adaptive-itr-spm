package com.adaptive.itr.spm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fault injection for the stream verification of StreamBench: each mutant engine below breaks
 * one step of the incremental procedure, and the same comparison against RemineTaSPM must
 * report at least one differing batch. A mutant that is not caught means the check cannot see
 * that step. The comparator itself is checked first on three perturbed maps.
 *
 * Usage: java -cp target/classes:<this> com.adaptive.itr.spm.VerifyMutants <stream config>
 * Exit code 0 when every mutant and every perturbation is caught, 1 otherwise.
 */
public class VerifyMutants {

    static IncAdaTaSPM.Engine base() {
        return new IncAdaTaSPM.Engine(AppConfig.MIN_SUP_RATE_EXACT, AppConfig.MAX_PER, AppConfig.TARGET_ITEMS,
                AppConfig.ETA, AppConfig.LAMBDA, AppConfig.W1, AppConfig.W3, AppConfig.W4)
                .stubFrac(AppConfig.PHI).lazy(AppConfig.LAZY);
    }

    interface Factory { IncAdaTaSPM.Engine make(); }

    public static void main(String[] a) throws Exception {
        AppConfig.CURRENT_CONFIG_FILE = AppConfig.resolveConfig(a[0]).getAbsolutePath();
        AppConfig.load();
        int fail = 0;

        // 1. comparator perturbations
        Map<String, Integer> ref = new HashMap<>();
        ref.put("1", 5); ref.put("1|2", 3); ref.put("1 2", 4);
        Map<String, Integer> m1 = new HashMap<>(ref); m1.remove("1|2");
        Map<String, Integer> m2 = new HashMap<>(ref); m2.put("1 2", 5);
        Map<String, Integer> m3 = new HashMap<>(ref); m3.put("3", 9);
        long[][] got = {StreamBench.compare(m1, ref), StreamBench.compare(m2, ref), StreamBench.compare(m3, ref), StreamBench.compare(ref, ref)};
        boolean okCmp = got[0][1] == 1 && got[1][2] == 1 && got[2][0] == 1 && got[3][0] + got[3][1] + got[3][2] == 0;
        System.out.println("comparator: missing/support/extra caught and identical maps clean = " + okCmp);
        if (!okCmp) fail++;

        // 2. mutant engines
        String[] names = {"no-promotion", "frozen-stub-counts", "no-rescan", "output-count-off-by-one"};
        Factory[] fs = {
                () -> new IncAdaTaSPM.Engine(AppConfig.MIN_SUP_RATE_EXACT, AppConfig.MAX_PER, AppConfig.TARGET_ITEMS,
                        AppConfig.ETA, AppConfig.LAMBDA, AppConfig.W1, AppConfig.W3, AppConfig.W4) {
                    @Override void promote(int P, IncAdaTaSPM.Proj pp, int d) { }
                }.stubFrac(AppConfig.PHI).lazy(AppConfig.LAZY),
                () -> new IncAdaTaSPM.Engine(AppConfig.MIN_SUP_RATE_EXACT, AppConfig.MAX_PER, AppConfig.TARGET_ITEMS,
                        AppConfig.ETA, AppConfig.LAMBDA, AppConfig.W1, AppConfig.W3, AppConfig.W4) {
                    @Override int stubOrMax(int P, int k, int cnt, int maxv) {
                        if (t.findKid(P, k) >= 0 || t.isDead(P, k)) return maxv;
                        if (t.findStub(P, k) >= 0) return maxv;          // batch support not added to the stub
                        return cnt > maxv ? cnt : maxv;
                    }
                }.stubFrac(AppConfig.PHI).lazy(AppConfig.LAZY),
                () -> new IncAdaTaSPM.Engine(AppConfig.MIN_SUP_RATE_EXACT, AppConfig.MAX_PER, AppConfig.TARGET_ITEMS,
                        AppConfig.ETA, AppConfig.LAMBDA, AppConfig.W1, AppConfig.W3, AppConfig.W4) {
                    @Override void markUp(int P, byte flag) { super.markUp(P, (byte) (flag & ~IncAdaTaSPM.Tree.RESC)); }
                }.stubFrac(AppConfig.PHI).lazy(AppConfig.LAZY),
                () -> new IncAdaTaSPM.Engine(AppConfig.MIN_SUP_RATE_EXACT, AppConfig.MAX_PER, AppConfig.TARGET_ITEMS,
                        AppConfig.ETA, AppConfig.LAMBDA, AppConfig.W1, AppConfig.W3, AppConfig.W4) {
                    @Override public long countOutput() { return super.countOutput() + 1; }
                }.stubFrac(AppConfig.PHI).lazy(AppConfig.LAZY)
        };
        StreamBench.Data d = StreamBench.load(AppConfig.DATASET_PATH);
        int[] ends = AppConfig.batchEnds(d.total);
        int[] diffs = new int[fs.length + 1];
        for (int m = 0; m <= fs.length; m++) {
            IncAdaTaSPM.Engine e = m < fs.length ? fs[m].make() : base();
            if (a.length > 1 && m < fs.length && !a[1].contains(names[m])) continue;
            diffs[m] = run(e, d, ends);
            String nm = m < fs.length ? names[m] : "unmodified engine (control)";
            System.out.println(nm + ": batches with a difference = " + diffs[m] + " of " + ends.length
                    + " | engine counters: rescans=" + e.rescans + " rescans_inserting=" + e.rescanInserts + " promotions=" + e.promotions);
            boolean ok = m < fs.length ? diffs[m] > 0 : diffs[m] == 0;
            if (!ok) fail++;
        }
        System.out.println(fail == 0 ? "ALL CAUGHT" : ("NOT CAUGHT: " + fail));
        System.exit(fail == 0 ? 0 : 1);
    }

    static int run(IncAdaTaSPM.Engine e, StreamBench.Data d, int[] ends) {
        List<int[][]> all = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        StreamBench.Arm arm = new StreamBench.EngineArm(e);
        StreamBench.Arm ref = StreamBench.newArm("RemineTaSPM", all, lines);
        int ti = 0, endOld = 0, bad = 0;
        for (int n : ends) {
            List<int[][]> batch = new ArrayList<>(); List<Integer> bl = new ArrayList<>();
            while (ti < d.seq.size() && d.line.get(ti) < n) { batch.add(d.seq.get(ti)); bl.add(d.line.get(ti)); ti++; }
            int[] pos = new int[bl.size()];
            for (int i = 0; i < pos.length; i++) pos[i] = bl.get(i) + 1;
            all.addAll(batch); lines.addAll(bl);
            arm.captureOutput(true); ref.captureOutput(true);
            long pa = arm.step(batch, bl, pos, endOld, n);
            long pr = ref.step(batch, bl, pos, endOld, n);
            long[] c = StreamBench.compare(arm.output(), ref.output());
            if (c[0] + c[1] + c[2] > 0 || pa != arm.output().size() || pr != ref.output().size()) bad++;
            endOld = n;
        }
        return bad;
    }
}
