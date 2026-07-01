package com.adaptive.itr.spm;

/**
 * Maps an algorithm short-name (as it appears in the {@code --algos=...}
 * CLI flag or in {@link ExperimentRunner#DEFAULT_ALGORITHMS}) to a
 * {@link Runnable} that executes one full benchmark run of that
 * algorithm against whatever configuration is currently loaded in
 * {@link AppConfig}.
 *
 * <p>Keeping this in one place lets the orchestrators stay data-driven
 * - they iterate over the requested algorithm names rather than
 * hard-coding an {@code if/else} chain - and makes adding a new
 * baseline a one-line change here.
 */
final class AlgorithmRegistry {

    private AlgorithmRegistry() {}

    /**
     * @return a Runnable that mines the currently configured dataset with
     *         the named algorithm, or {@code null} if the name is not
     *         recognised.
     */
    static Runnable get(String name) {
        if (name == null) return null;
        switch (name.trim()) {
            case "AdaIncTaSPM":       return () -> new AdaIncTaSPM().run();
            case "IncTaSPM_Li":       return () -> new IncTaSPM_Li().run();
            case "IncStaticTaSPM":    return () -> new IncStaticTaSPM().run();
            case "TalentNoReg":       return () -> new TalentNoReg().run();
            case "BatchTaSPM":        return () -> new BatchTaSPM().run();
            case "PrefixSpanTarget":  return () -> new PrefixSpanTarget().run();
            default: return null;
        }
    }
}
