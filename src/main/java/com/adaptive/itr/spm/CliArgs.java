package com.adaptive.itr.spm;

/**
 * Tiny command-line-argument helper shared by every experiment runner.
 *
 * <p>Each runner uses a small, uniform vocabulary of comma-separated
 * list flags (e.g. {@code --configs=a.txt,b.txt},
 * {@code --lambdas=0.1,0.3,0.5}) rather than a full-blown args4j /
 * picocli dependency; this class centralises the parsing so the
 * runners themselves stay declarative.
 *
 * <p>Two syntaxes are accepted per flag:
 * <ul>
 *   <li>{@code --flag=v1,v2,v3} - attached form;</li>
 *   <li>{@code --flag v1,v2,v3} - separated form.</li>
 * </ul>
 * A flag that is not supplied on the command line falls back to the
 * caller-supplied default array.
 */
final class CliArgs {

    private CliArgs() {}

    /** Extract a comma-separated string list bound to {@code flag}. */
    static String[] getList(String[] args, String flag, String[] defaults) {
        String raw = getRaw(args, flag);
        if (raw == null) return defaults;
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }

    /** Comma-separated list of doubles. */
    static double[] getDoubleList(String[] args, String flag, double[] defaults) {
        String raw = getRaw(args, flag);
        if (raw == null) return defaults;
        String[] parts = raw.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Double.parseDouble(parts[i].trim());
        return out;
    }

    /** Comma-separated list of ints. */
    static int[] getIntList(String[] args, String flag, int[] defaults) {
        String raw = getRaw(args, flag);
        if (raw == null) return defaults;
        String[] parts = raw.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }

    /**
     * Return the raw string bound to {@code flag}, or {@code null} if the
     * flag is not present. Accepts both the {@code --flag=value} and
     * {@code --flag value} forms.
     */
    static String getRaw(String[] args, String flag) {
        if (args == null) return null;
        String eqPrefix = flag + "=";
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null) continue;
            if (a.startsWith(eqPrefix)) return a.substring(eqPrefix.length());
            if (a.equals(flag) && i + 1 < args.length) return args[i + 1];
        }
        return null;
    }
}
