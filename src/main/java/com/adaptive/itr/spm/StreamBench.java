package com.adaptive.itr.spm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

/**
 * Stream benchmark used for every measured run: one dataset, one arm, one mode per JVM.
 *
 * <p>The dataset is read and parsed once, before any clock starts, for every arm alike. Every
 * non-empty line is one sequence and counts in N; sequences without a target item are counted
 * but not handed to the arms. The stream is D_init (the first {@code init_size} sequences)
 * followed by {@code batch_count} increments ({@link AppConfig#batchEnds}). Batch 0 is the
 * initial mine of D_init.
 *
 * <p>What one batch's clock covers, per arm (System.nanoTime around one call):
 * <ul>
 *   <li>IncAdaTaSPM and its variants: {@code Engine.addBatch} (appending the batch to D_T and
 *       to the item occurrence lists, the tree walk, rescans, promotions, expansions) and
 *       {@code Engine.countOutput} (counting the output set).</li>
 *   <li>RemineTaSPM: building its arrays and per-sequence item cache for the whole current
 *       database, item scores, item bitmaps and occurrence lists, and the full DFS; nothing is
 *       kept from the previous batch.</li>
 *   <li>BatchTaSPM: building its filtered database for the whole current database and the full
 *       DFS; nothing is kept from the previous batch.</li>
 *   <li>ItemPreLargeTaSPM: copying the current database into its arrays, the item-level
 *       pre-large update with backward scans, and the full DFS over the tracked items.</li>
 * </ul>
 * Outside every clock: reading and parsing the file, a forced collection and a 100 ms pause
 * before each batch (time mode), collecting counters after the batch.
 *
 * <p>Modes: {@code time} (runtime; warm-up streams then measured repetitions in this JVM),
 * {@code live} (live set: a sampler thread forces a full collection and reads the used heap;
 * runtimes are void), {@code verify} (the arm and the reference RemineTaSPM after every batch,
 * pattern sets and supports compared exactly; exit code 3 on any difference).
 */
public final class StreamBench {

    // ------------------------------------------------------------------ arms
    interface Arm {
        /** Processes one batch; returns the number of output patterns. Timed. */
        long step(List<int[][]> batch, List<Integer> batchLines, int[] positions, int endOld, int n);
        /** Counters after the batch (not timed); cumulative values are differenced by the caller. */
        void counters(Map<String, Long> out);
        /** Next step also fills the output map (verify mode). */
        void captureOutput(boolean on);
        Map<String, Integer> output();
    }

    static final String[] ARMS = {
            "IncAdaTaSPM",              // the method
            "IncAdaTaSPM-NoBuffer",     // eta = 0
            "IncAdaTaSPM-StaticPreLarge", // Score = 1 for every pattern: LB = (1 - eta) min_sup
            "IncAdaTaSPM-Original",     // design procedure: phi = 1, lazy insertion off
            "IncAdaTaSPM-TBR",          // with the target-proximity component (w_tbr = w2)
            "RemineTaSPM",              // re-mine the whole database after every batch
            "BatchTaSPM",               // TaSPM-style batch miner re-run after every batch
            "ItemPreLargeTaSPM"         // self-built item-level pre-large baseline
    };

    static final class EngineArm implements Arm {
        final IncAdaTaSPM.Engine e;
        boolean capture;
        Map<String, Integer> out;
        EngineArm(IncAdaTaSPM.Engine e) { this.e = e; }
        public long step(List<int[][]> batch, List<Integer> lines, int[] positions, int endOld, int n) {
            e.addBatch(batch, positions, n);
            long k = e.countOutput();
            if (capture) {
                List<String> ps = new ArrayList<>(); List<Integer> ss = new ArrayList<>();
                e.collect(ps, ss);
                out = new HashMap<>(ps.size() * 2);
                for (int i = 0; i < ps.size(); i++) out.put(ps.get(i), ss.get(i));
            }
            return k;
        }
        public void counters(Map<String, Long> m) {
            long[] tc = e.treeCounts();
            m.put("rescans", e.rescans); m.put("rescan_inserts", e.rescanInserts); m.put("promotions", e.promotions); m.put("projections", e.projections);
            m.put("live_nodes", tc[0]); m.put("tombstones", tc[1]); m.put("stubs", tc[2]); m.put("f_size", tc[3]);
            m.put("min_sup", (long) e.minSup());
        }
        public void captureOutput(boolean on) { capture = on; }
        public Map<String, Integer> output() { return out; }
    }

    static final class RemineArm implements Arm {
        final RemineTaSPM r = new RemineTaSPM();
        final List<int[][]> all; final List<Integer> allLines;
        RemineArm(List<int[][]> all, List<Integer> allLines) { this.all = all; this.allLines = allLines; }
        public long step(List<int[][]> batch, List<Integer> lines, int[] positions, int endOld, int n) {
            return r.mineSnapshot(all, allLines, endOld, n);
        }
        public void counters(Map<String, Long> m) { m.put("projections", r.projections); }
        public void captureOutput(boolean on) { r.sink = on ? new HashMap<String, Integer>() : null; }
        public Map<String, Integer> output() { return r.sink; }
    }

    static final class BatchArm implements Arm {
        final BatchTaSPM r = new BatchTaSPM();
        final List<int[][]> all; final List<Integer> allLines;
        BatchArm(List<int[][]> all, List<Integer> allLines) { this.all = all; this.allLines = allLines; }
        public long step(List<int[][]> batch, List<Integer> lines, int[] positions, int endOld, int n) {
            return r.mineSnapshot(all, allLines, n);
        }
        public void counters(Map<String, Long> m) { }
        public void captureOutput(boolean on) { r.sink = on ? new HashMap<String, Integer>() : null; }
        public Map<String, Integer> output() { return r.sink; }
    }

    static final class ItemPreLargeArm implements Arm {
        final ItemPreLargeTaSPM r = new ItemPreLargeTaSPM();
        long backscans = 0;
        public long step(List<int[][]> batch, List<Integer> lines, int[] positions, int endOld, int n) {
            long k = r.addBatch(batch, lines, endOld, n);
            backscans += r.lastBackscans();
            return k;
        }
        public void counters(Map<String, Long> m) { m.put("backscans", backscans); }
        public void captureOutput(boolean on) { r.sink = on ? new HashMap<String, Integer>() : null; }
        public Map<String, Integer> output() { return r.sink; }
    }

    /** Builds a fresh instance of the named arm from the loaded configuration. */
    static Arm newArm(String name, List<int[][]> all, List<Integer> allLines) {
        switch (name) {
            case "RemineTaSPM": return new RemineArm(all, allLines);
            case "BatchTaSPM": return new BatchArm(all, allLines);
            case "ItemPreLargeTaSPM": return new ItemPreLargeArm();
            default: break;
        }
        double eta = AppConfig.ETA;
        double phi = AppConfig.PHI;
        boolean lazy = AppConfig.LAZY;
        boolean scoreConst = "const".equals(AppConfig.SCORE_MODE);
        double wT = AppConfig.W_TBR;
        switch (name) {
            case "IncAdaTaSPM": break;
            case "IncAdaTaSPM-NoBuffer": eta = 0.0; break;
            case "IncAdaTaSPM-StaticPreLarge": scoreConst = true; break;
            case "IncAdaTaSPM-Original": phi = 1.0; lazy = false; break;
            case "IncAdaTaSPM-TBR": wT = AppConfig.W2; break;
            default: throw new IllegalArgumentException("unknown arm '" + name + "'; known: " + String.join(", ", ARMS));
        }
        IncAdaTaSPM.Engine e = new IncAdaTaSPM.Engine(AppConfig.MIN_SUP_RATE_EXACT, AppConfig.MAX_PER, AppConfig.TARGET_ITEMS,
                eta, AppConfig.LAMBDA, AppConfig.W1, AppConfig.W3, AppConfig.W4)
                .stubFrac(phi).lazy(lazy).scoreConst(scoreConst).targetProximityWeight(wT);
        return new EngineArm(e);
    }

    /** Effective engine parameters of an arm, for the run record. */
    static String armParams(String name) {
        Arm a = newArm(name, new ArrayList<int[][]>(), new ArrayList<Integer>());
        if (!(a instanceof EngineArm)) return "buffer_ratio=" + AppConfig.BUFFER_RATIO;
        IncAdaTaSPM.Engine e = ((EngineArm) a).e;
        return String.format(Locale.US, "eta=%s lambda=%s wV=%s wR=%s wS=%s wTBR=%s phi=%s lazy=%s score=%s",
                e.eta, e.lam, e.wV, e.wR, e.wS, e.wT, e.stubFrac(), e.lazy(), e.scoreConst ? "const" : "adaptive");
    }

    // ------------------------------------------------------------------ data
    static final class Data {
        int total;                       // non-empty lines
        final List<int[][]> seq = new ArrayList<>();   // target sequences, file order
        final List<Integer> line = new ArrayList<>();  // 0-based line of each target sequence
        long loadMs;
        String sha256;
    }

    static Data load(String path) throws IOException {
        Data d = new Data();
        long t0 = System.nanoTime();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8), 1 << 16)) {
            String s;
            while ((s = br.readLine()) != null) {
                if (s.trim().isEmpty()) continue;
                int[][] q = IncAdaTaSPM.parse(s);
                if (q != null && IncAdaTaSPM.containsTarget(q)) { d.seq.add(q); d.line.add(d.total); }
                d.total++;
            }
        }
        d.loadMs = (System.nanoTime() - t0) / 1_000_000;
        d.sha256 = sha256(new File(path));
        return d;
    }

    // ------------------------------------------------------------------ live-set sampler
    static final class Sampler extends Thread {
        final MemoryMXBean mx = ManagementFactory.getMemoryMXBean();
        final long intervalMs;
        volatile boolean stop = false;
        volatile long peak = 0;
        volatile long samples = 0;
        Sampler(long intervalMs) { this.intervalMs = intervalMs; setDaemon(true); }
        long sample() {
            long g0 = System.nanoTime();
            System.gc();
            long used = mx.getHeapMemoryUsage().getUsed();
            long gcMs = (System.nanoTime() - g0) / 1_000_000;
            samples++;
            if (used > peak) peak = used;
            return gcMs;
        }
        public void run() {
            while (!stop) {
                long gcMs = sample();
                long wait = Math.max(intervalMs, 4 * gcMs);
                try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
            }
        }
    }

    static long gcNow() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    // ------------------------------------------------------------------ main
    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new LinkedHashMap<>();
        List<String> overrides = new ArrayList<>();
        for (String a : args) {
            if (!a.startsWith("--") || !a.contains("=")) die(2, "argument '" + a + "' is not of the form --key=value");
            String k = a.substring(2, a.indexOf('=')), v = a.substring(a.indexOf('=') + 1);
            switch (k) {
                case "config": case "arm": case "mode": case "out": case "reps": case "warmup":
                case "timeout-min": case "tag": case "mem-interval-ms": case "ref": case "lock":
                    opt.put(k, v); break;
                default: overrides.add(k + "=" + v);
            }
        }
        String cfg = need(opt, "config"), arm = need(opt, "arm"), mode = opt.getOrDefault("mode", "time");
        if (!mode.equals("time") && !mode.equals("live") && !mode.equals("verify")) die(2, "--mode must be time, live or verify, got '" + mode + "'");
        File cfgFile = AppConfig.resolveConfig(cfg);
        if (!cfgFile.isFile()) die(2, "configuration file not found: " + cfgFile.getAbsolutePath());
        AppConfig.CURRENT_CONFIG_FILE = cfgFile.getAbsolutePath();
        AppConfig.load();
        for (String ov : overrides) {
            String k = ov.substring(0, ov.indexOf('=')), v = ov.substring(ov.indexOf('=') + 1);
            if (!AppConfig.set(k, v)) die(2, "unknown option --" + k);
        }
        if (AppConfig.INIT_SIZE <= 0) die(2, "configuration " + cfgFile.getName() + " has no init_size; the measured stream needs init_size and batch_count");
        if (!new File(AppConfig.DATASET_PATH).isFile()) die(2, "dataset not found: " + AppConfig.DATASET_PATH);
        boolean known = false; for (String x : ARMS) known |= x.equals(arm);
        if (!known) die(2, "unknown arm '" + arm + "'; known: " + String.join(", ", ARMS));

        File outDir = new File(opt.getOrDefault("out", "results-bench"));
        if (!outDir.isDirectory() && !outDir.mkdirs()) die(2, "cannot create output directory " + outDir.getAbsolutePath());
        if (opt.containsKey("lock")) checkLock(new File(opt.get("lock")));
        String ds = new File(AppConfig.DATASET_PATH).getName().replaceAll("\\.txt$", "");
        String tag = opt.getOrDefault("tag", "");
        if (tag.equals("-")) tag = "";
        String stem = ds + "__" + arm + (tag.isEmpty() ? "" : "__" + tag) + "__" + mode;
        File csv = new File(outDir, stem + ".csv");
        if (csv.exists()) die(4, "refusing to overwrite " + csv.getAbsolutePath() + " (a finished run of the same dataset, arm, tag and mode)");

        Data data = load(AppConfig.DATASET_PATH);
        int[] ends = AppConfig.batchEnds(data.total);
        String started = now();
        writeMachine(new File(outDir, "machine.txt"));
        PrintWriter meta = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(outDir, stem + ".meta.txt")), StandardCharsets.UTF_8));
        meta.println("command=StreamBench " + String.join(" ", args));
        meta.println("started=" + started);
        Properties bi = buildInfo();
        for (String k : bi.stringPropertyNames()) meta.println("build." + k + "=" + bi.getProperty(k));
        meta.println("jar_sha256=" + jarSha256());
        meta.println("config_file=" + cfgFile.getName());
        meta.println("config_sha256=" + sha256(cfgFile));
        meta.println("dataset=" + new File(AppConfig.DATASET_PATH).getName());
        meta.println("dataset_sha256=" + data.sha256);
        meta.println("sequences=" + data.total + " target_sequences=" + data.seq.size());
        meta.println("init_size=" + AppConfig.INIT_SIZE + " batch_count=" + AppConfig.BATCH_COUNT + " increment_sizes=" + incrementSizes(ends));
        meta.println("min_sup_rate=" + AppConfig.MIN_SUP_RATE_EXACT.toPlainString() + " max_per=" + AppConfig.MAX_PER + " targets=" + AppConfig.TARGET_ITEMS);
        meta.println("arm=" + arm + " params: " + armParams(arm));
        meta.println("load_ms=" + data.loadMs + " (reading and parsing the file, outside every clock)");
        meta.println("java=" + System.getProperty("java.runtime.version") + " vendor=" + System.getProperty("java.vendor")
                + " max_heap_mb=" + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " jvm_args=" + jvmArgs());
        meta.flush();

        PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(outDir, stem + ".csv.part")), StandardCharsets.UTF_8));
        out.println("dataset,arm,tag,mode,rep,batch,n_total,n_new,dt_total,min_sup,time_ms,cum_ms,patterns,rescans,rescan_inserts,promotions,projections,live_nodes,tombstones,stubs,f_size,backscans,live_mb,retained_mb,gc_forced,cmp_ref_patterns,cmp_only_arm,cmp_only_ref,cmp_sup_diff,status");
        String prefix = csvq(ds) + "," + csvq(arm) + "," + csvq(tag) + "," + mode + ",";

        int exit = 0;
        long timeoutMs = (long) (Double.parseDouble(opt.getOrDefault("timeout-min", "120")) * 60_000);
        if (mode.equals("time")) {
            int warm = Integer.parseInt(opt.getOrDefault("warmup", "1"));
            String repsOpt = opt.getOrDefault("reps", "auto");
            int reps = repsOpt.equals("auto") ? -1 : Integer.parseInt(repsOpt);
            meta.println("warmup_streams=" + warm + " reps=" + repsOpt + (reps < 0 ? " (rule: from the first measured stream's total time T: T<1s 15, T<10s 10, T<120s 5, else 3)" : ""));
            for (int w = 1; w <= warm; w++) {
                String st = stream(arm, data, ends, "time", out, prefix, -w, timeoutMs, 0, null);
                if (!st.equals("OK")) { meta.println("warmup_status=" + st); exit = 5; break; }
            }
            if (exit == 0) {
                for (int r = 1; reps < 0 || r <= reps; r++) {
                    long[] tot = new long[1];
                    String st = stream(arm, data, ends, "time", out, prefix, r, timeoutMs, 0, tot);
                    if (r == 1 && reps < 0) {
                        double s = tot[0] / 1e9;
                        reps = s < 1 ? 15 : s < 10 ? 10 : s < 120 ? 5 : 3;
                        meta.println(String.format(Locale.US, "first_stream_s=%.3f reps_chosen=%d", s, reps));
                    }
                    if (!st.equals("OK")) { meta.println("rep" + r + "_status=" + st); exit = 5; break; }
                }
            }
        } else if (mode.equals("live")) {
            long iv = Long.parseLong(opt.getOrDefault("mem-interval-ms", "20"));
            meta.println("live: sampler forces a full collection and reads the used heap; waits max(" + iv + " ms, 4 x collection time); baseline = used heap after loading; runtimes void");
            String st = stream(arm, data, ends, "live", out, prefix, 1, timeoutMs, iv, null);
            if (!st.equals("OK")) { meta.println("status=" + st); exit = 5; }
        } else {
            String ref = opt.getOrDefault("ref", "RemineTaSPM");
            meta.println("verify: reference arm " + ref + ", exact comparison of pattern sets and supports after every batch");
            int bad = verify(arm, ref, data, ends, out, prefix, timeoutMs, meta);
            if (bad < 0) exit = 5; else if (bad > 0) exit = 3;
        }
        out.close();
        meta.println("finished=" + now() + " exit=" + exit);
        meta.close();
        if (exit == 0 || exit == 3 || exit == 5) {
            if (!new File(outDir, stem + ".csv.part").renameTo(csv)) die(2, "cannot rename result file to " + csv);
        }
        System.out.println("[StreamBench] " + stem + " exit=" + exit);
        System.exit(exit);
    }

    /** Runs one stream; returns OK, TIMEOUT or OOM. tot[0] receives the total timed ns. */
    static String stream(String armName, Data d, int[] ends, String mode, PrintWriter out, String prefix,
                         int rep, long timeoutMs, long memIntervalMs, long[] tot) {
        Map<String, Long> prev = new HashMap<>(), cur = new HashMap<>();
        long cum = 0;
        long baseline = mode.equals("live") ? gcNow() : 0;
        List<int[][]> all = new ArrayList<>(d.seq.size());
        List<Integer> allLines = new ArrayList<>(d.seq.size());
        Arm a = newArm(armName, all, allLines);
        int ti = 0, endOld = 0;
        try {
            for (int b = 0; b < ends.length; b++) {
                int n = ends[b];
                List<int[][]> batch = new ArrayList<>();
                List<Integer> bl = new ArrayList<>();
                while (ti < d.seq.size() && d.line.get(ti) < n) { batch.add(d.seq.get(ti)); bl.add(d.line.get(ti)); ti++; }
                int[] positions = new int[bl.size()];
                for (int i = 0; i < positions.length; i++) positions[i] = bl.get(i) + 1;
                all.addAll(batch); allLines.addAll(bl);
                Sampler sm = null;
                if (mode.equals("time")) {
                    System.gc();
                    try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                } else {
                    sm = new Sampler(memIntervalMs);
                    sm.start();
                }
                long t0 = System.nanoTime();
                long pats = a.step(batch, bl, positions, endOld, n);
                long dt = System.nanoTime() - t0;
                long retained = 0;
                if (sm != null) {
                    sm.stop = true; sm.interrupt();
                    try { sm.join(); } catch (InterruptedException ignored) { }
                    retained = gcNow();
                    if (retained > sm.peak) sm.peak = retained;
                    sm.samples++;
                }
                cum += dt;
                cur.clear(); a.counters(cur);
                StringBuilder r = new StringBuilder(prefix);
                r.append(rep).append(',').append(b).append(',').append(n).append(',').append(n - endOld).append(',')
                        .append(all.size()).append(',').append(val(cur, "min_sup", AppConfig.minSup(n))).append(',');
                if (mode.equals("time")) r.append(String.format(Locale.US, "%.3f,%.3f,", dt / 1e6, cum / 1e6));
                else r.append("-3,-3,");
                r.append(pats).append(',')
                        .append(delta(cur, prev, "rescans")).append(',').append(delta(cur, prev, "rescan_inserts")).append(',').append(delta(cur, prev, "promotions")).append(',')
                        .append(delta(cur, prev, "projections")).append(',')
                        .append(val(cur, "live_nodes", -1)).append(',').append(val(cur, "tombstones", -1)).append(',')
                        .append(val(cur, "stubs", -1)).append(',').append(val(cur, "f_size", -1)).append(',')
                        .append(delta(cur, prev, "backscans")).append(',');
                if (sm != null) r.append(String.format(Locale.US, "%.3f,%.3f,%d,", (sm.peak - baseline) / 1048576.0, (retained - baseline) / 1048576.0, sm.samples));
                else r.append("-1,-1,0,");
                r.append(",,,,OK");
                out.println(r); out.flush();
                prev = new HashMap<>(cur);
                endOld = n;
                if (cum / 1_000_000 > timeoutMs) { row(out, prefix, rep, b + 1, "TIMEOUT"); return "TIMEOUT"; }
            }
        } catch (OutOfMemoryError oom) {
            a = null; all.clear(); System.gc();
            row(out, prefix, rep, -1, "OOM");
            return "OOM";
        }
        if (tot != null) tot[0] = cum;
        return "OK";
    }

    /** Verify mode: returns the number of batches with a difference, or -1 on OOM. */
    static int verify(String armName, String refName, Data d, int[] ends, PrintWriter out, String prefix, long timeoutMs, PrintWriter meta) {
        List<int[][]> all = new ArrayList<>(d.seq.size());
        List<Integer> allLines = new ArrayList<>(d.seq.size());
        Arm a = newArm(armName, all, allLines);
        Arm ref = newArm(refName, all, allLines);
        a.captureOutput(true); ref.captureOutput(true);
        int ti = 0, endOld = 0, bad = 0;
        long compared = 0, refTotal = 0;
        Map<String, Long> cur = new HashMap<>(), prev = new HashMap<>();
        try {
            for (int b = 0; b < ends.length; b++) {
                int n = ends[b];
                List<int[][]> batch = new ArrayList<>();
                List<Integer> bl = new ArrayList<>();
                while (ti < d.seq.size() && d.line.get(ti) < n) { batch.add(d.seq.get(ti)); bl.add(d.line.get(ti)); ti++; }
                int[] positions = new int[bl.size()];
                for (int i = 0; i < positions.length; i++) positions[i] = bl.get(i) + 1;
                all.addAll(batch); allLines.addAll(bl);
                a.captureOutput(true); ref.captureOutput(true);
                long pa = a.step(batch, bl, positions, endOld, n);
                long pr = ref.step(batch, bl, positions, endOld, n);
                Map<String, Integer> ma = a.output(), mr = ref.output();
                long[] c = compare(ma, mr);
                boolean countsAgree = pa == ma.size() && pr == mr.size();
                if (c[0] + c[1] + c[2] > 0 || !countsAgree) bad++;
                compared++; refTotal += mr.size();
                cur.clear(); a.counters(cur);
                StringBuilder r = new StringBuilder(prefix);
                r.append(1).append(',').append(b).append(',').append(n).append(',').append(n - endOld).append(',')
                        .append(all.size()).append(',').append(AppConfig.minSup(n)).append(",-3,-3,").append(pa).append(',')
                        .append(delta(cur, prev, "rescans")).append(',').append(delta(cur, prev, "rescan_inserts")).append(',').append(delta(cur, prev, "promotions")).append(',')
                        .append(delta(cur, prev, "projections")).append(',')
                        .append(val(cur, "live_nodes", -1)).append(',').append(val(cur, "tombstones", -1)).append(',')
                        .append(val(cur, "stubs", -1)).append(',').append(val(cur, "f_size", -1)).append(',')
                        .append(delta(cur, prev, "backscans")).append(",-1,-1,0,")
                        .append(mr.size()).append(',').append(c[0]).append(',').append(c[1]).append(',').append(c[2]).append(',')
                        .append(c[0] + c[1] + c[2] == 0 && countsAgree ? "SAME" : "DIFF");
                out.println(r); out.flush();
                prev = new HashMap<>(cur);
                endOld = n;
            }
        } catch (OutOfMemoryError oom) {
            all.clear(); System.gc();
            row(out, prefix, 1, -1, "OOM");
            meta.println("verify_status=OOM");
            return -1;
        }
        meta.println("verify_batches_compared=" + compared + " batches_with_difference=" + bad + " reference_patterns_summed_over_batches=" + refTotal);
        return bad;
    }

    /** {only in a, only in b, in both with a different support}. */
    static long[] compare(Map<String, Integer> a, Map<String, Integer> b) {
        long onlyA = 0, onlyB = 0, sup = 0;
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            Integer v = b.get(e.getKey());
            if (v == null) onlyA++; else if (!v.equals(e.getValue())) sup++;
        }
        for (String k : b.keySet()) if (!a.containsKey(k)) onlyB++;
        return new long[]{onlyA, onlyB, sup};
    }

    /** JVM input arguments; an agent or any argument holding a path is reduced to its option name,
     *  so the record carries no path of the machine. */
    static String jvmArgs() {
        List<String> r = new ArrayList<>();
        for (String x : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (x.startsWith("-javaagent") || x.startsWith("-agentpath") || x.contains("/") || x.contains("\\")) {
                int k = x.indexOf(':') > 0 ? x.indexOf(':') : (x.indexOf('=') > 0 ? x.indexOf('=') : x.length());
                r.add(x.substring(0, k) + "=<path omitted>");
            } else r.add(x);
        }
        return r.toString();
    }

    /** Runtime lock: the first run writes the JVM identity; later runs refuse a different one. */
    static void checkLock(File f) throws IOException {
        String mine = "java.runtime.version=" + System.getProperty("java.runtime.version") + "\njava.vendor=" + System.getProperty("java.vendor") + "\n";
        if (!f.exists()) {
            try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) { w.print(mine); }
            return;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l; while ((l = br.readLine()) != null) if (!l.trim().isEmpty()) sb.append(l.trim()).append('\n');
        }
        if (!sb.toString().equals(mine))
            die(6, "runtime differs from the lock " + f.getAbsolutePath() + ": locked [" + sb.toString().replace('\n', ' ').trim()
                    + "], running [" + mine.replace('\n', ' ').trim() + "]; use the locked JDK or start a new results directory");
    }

    // ------------------------------------------------------------------ helpers
    static void row(PrintWriter out, String prefix, int rep, int batch, String status) {
        out.println(prefix + rep + "," + batch + ",,,,,,,,,,,,,,,,,,,,,,,," + status);
        out.flush();
    }
    static long delta(Map<String, Long> c, Map<String, Long> p, String k) {
        if (!c.containsKey(k)) return -1;
        return c.get(k) - p.getOrDefault(k, 0L);
    }
    static long val(Map<String, Long> c, String k, long dflt) { Long v = c.get(k); return v == null ? dflt : v; }
    static String csvq(String s) { return s.contains(",") ? "\"" + s + "\"" : s; }
    static String need(Map<String, String> o, String k) { String v = o.get(k); if (v == null) die(2, "missing --" + k + "=..."); return v; }
    static void die(int code, String msg) { System.err.println("[StreamBench] " + msg); System.exit(code); }
    static String now() { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX"); f.setTimeZone(TimeZone.getDefault()); return f.format(new Date()); }

    static String incrementSizes(int[] ends) {
        int mn = Integer.MAX_VALUE, mx = 0;
        for (int k = 1; k < ends.length; k++) { int s = ends[k] - ends[k - 1]; mn = Math.min(mn, s); mx = Math.max(mx, s); }
        return mn == mx ? String.valueOf(mn) : mn + ".." + mx;
    }

    static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return "unavailable"; }
    }

    static String jarSha256() {
        try {
            File f = new File(StreamBench.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() ? sha256(f) : "not-a-jar (" + f.getName() + ")";
        } catch (Exception e) { return "unavailable"; }
    }

    static Properties buildInfo() {
        Properties p = new Properties();
        try (InputStream in = StreamBench.class.getResourceAsStream("/build-info.properties")) {
            if (in != null) p.load(in); else p.setProperty("status", "missing (not built by tools/build_bench_jar.sh)");
        } catch (IOException e) { p.setProperty("status", "unreadable"); }
        return p;
    }

    /** Machine, runtime and time of the run; rewritten by every run into the output directory. */
    static void writeMachine(File f) {
        StringBuilder sb = new StringBuilder();
        String host;
        try { host = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { host = System.getenv("COMPUTERNAME"); }
        sb.append("written=").append(now()).append('\n');
        sb.append("hostname=").append(host).append('\n');
        sb.append("os=").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(" arch=").append(System.getProperty("os.arch")).append('\n');
        String cpu = System.getenv("PROCESSOR_IDENTIFIER");
        sb.append("cpu=").append(cpu == null ? "unknown" : cpu).append(" logical_processors=").append(Runtime.getRuntime().availableProcessors()).append('\n');
        long ram = -1;
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            ram = (Long) os.getClass().getMethod("getTotalPhysicalMemorySize").invoke(os);
        } catch (Exception ignored) { }
        sb.append("physical_memory_mb=").append(ram < 0 ? "unknown" : String.valueOf(ram / (1024 * 1024))).append('\n');
        sb.append("java.runtime.version=").append(System.getProperty("java.runtime.version")).append('\n');
        sb.append("java.vendor=").append(System.getProperty("java.vendor")).append('\n');
        sb.append("java.vm.name=").append(System.getProperty("java.vm.name")).append('\n');
        sb.append("max_heap_mb=").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append('\n');
        StringBuilder gcs = new StringBuilder();
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) gcs.append(g.getName()).append(';');
        sb.append("collectors=").append(gcs).append('\n');
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) { w.print(sb); }
        catch (IOException ignored) { }
    }
}
