"""Python prototype of the incremental Ada-IncTaSPM procedure (prefix tree with a bound b on
the support of untracked children, rescans, tombstones), without stubs or lazy insertion.

Sequence = list of itemsets (frozenset). Pattern = tuple of sorted item tuples.
Support is counted on D_T (only sequences holding an item of T). Position = original index + 1.
"""
import math
from fractions import Fraction


# ---------------------------------------------------------------- helpers
def contains(seq, pat):
    """True if pat is a subsequence of seq (earliest greedy match)."""
    j = 0
    for its in seq:
        if j < len(pat) and set(pat[j]) <= its:
            j += 1
    return j == len(pat)


def children_in(seq, pat):
    """Every one-step extension (i-ext, s-ext) of pat contained in seq."""
    out = set()
    items = set().union(*seq) if seq else set()
    for x in items:
        if pat:
            last = pat[-1]
            if x > last[-1]:
                q = pat[:-1] + (tuple(sorted(last + (x,))),)
                if contains(seq, q):
                    out.add(q)
        q = pat + ((x,),)
        if contains(seq, q):
            out.add(q)
    return out


def min_sup_of(sigma, N):
    """min_sup = max(1, ceil(sigma*N)) in exact rational arithmetic."""
    return max(1, math.ceil(sigma * N))


def has_target(pat, T):
    return any(x in T for its in pat for x in its)


# ---------------------------------------------------------------- reference: re-mine from scratch
def remine(DT, N, sigma, max_per, T):
    """DT: list of (position, sequence). Returns the exact output pattern set."""
    ms = min_sup_of(sigma, N)
    res = set()

    def stats(pat):
        pos = [p for p, s in DT if contains(s, pat)]
        return pos

    def regular(pos):
        prev, g = 0, 0
        for p in pos:
            g = max(g, p - prev)
            prev = p
        return pos and max(g, N + 1 - prev) <= max_per

    def dfs(pat, seqs):
        cand = set()
        for p, s in seqs:
            cand |= children_in(s, pat)
        for q in cand:
            sq = [(p, s) for p, s in seqs if contains(s, q)]
            if len(sq) >= ms:
                if has_target(q, T) and regular([p for p, _ in sq]):
                    res.add(q)
                dfs(q, sq)

    dfs((), DT)
    return res


# ---------------------------------------------------------------- incremental version
class Node:
    __slots__ = ("pat", "parent", "kids", "dead", "sup", "sup_prev", "last",
                 "g", "wc", "wm", "wM2", "b", "anchor_N", "anchor_b",
                 "sup_old_batch", "rsum", "rcnt")

    def __init__(self, pat, parent):
        self.pat, self.parent = pat, parent
        self.kids = {}
        self.dead = False
        self.sup = self.sup_prev = self.last = self.g = 0
        self.wc, self.wm, self.wM2 = 0, 0.0, 0.0
        self.b = 0
        self.anchor_N = self.anchor_b = 0
        self.rsum, self.rcnt = 0.0, 0

    def add_gap(self, gap):
        self.wc += 1
        d = gap - self.wm
        self.wm += d / self.wc
        self.wM2 += d * (gap - self.wm)

    def add_pos(self, p):
        gap = p - self.last
        self.g = max(self.g, gap)
        self.add_gap(gap)
        self.last = p
        self.sup += 1


class IncMiner:
    def __init__(self, sigma, max_per, T, eta, w=(1.0, 1.0, 1.0), lam=0.1):
        assert 0 <= eta < 1
        self.sigma, self.max_per, self.T, self.eta = sigma, max_per, T, eta
        self.w, self.lam = w, lam
        self.DT = []          # (position, sequence) of D_T
        self.N = 0
        self.root = Node((), None)
        self.rescans = 0
        self.first = True
        self.nD_old = 0       # D_T sequences before the current batch
        self.nD_new = 0

    # ---- statistics of one pattern on the full D_T (used on insertion and rescans)
    def full_node(self, pat, parent):
        n = Node(pat, parent)
        for p, s in self.DT:
            if contains(s, pat):
                n.add_pos(p)
        return n

    def open_gap(self, n):
        return self.N + 1 - n.last

    def irregular(self, n):
        return n.sup == 0 or max(n.g, self.open_gap(n)) > self.max_per

    def score(self, n, sup_delta, rsum, rcnt):
        wv, wr, ws = self.w
        old = n.sup - sup_delta
        if self.first or old == 0 or self.nD_old == 0 or self.nD_new == 0:
            V = 1.0
        else:
            V = min(1.0, (sup_delta / self.nD_new) / (old / self.nD_old))
        R = rsum / rcnt if rcnt else 0.0
        if n.wc > 1 and n.wm > 0:
            sd = math.sqrt(max(0.0, n.wM2 / n.wc))
            S = 1.0 - min(1.0, sd / n.wm)
        else:
            S = 0.0
        return (wv * V + wr * R + ws * S) / (wv + wr + ws)

    def LB(self, n, sup_delta=0, rsum=0.0, rcnt=0):
        ms = min_sup_of(self.sigma, self.N)
        return ms * (1 - self.eta * self.score(n, sup_delta, rsum, rcnt))

    # ---- insertion and DFS (steps 5 and 6)
    def expand(self, P):
        """Counts every child of P on the full D_T; inserts the children reaching LB (irregular
        ones become tombstones), sets the tight b(P) and its anchor; DFS from the new children."""
        cand = set()
        for p, s in self.DT:
            if contains(s, P.pat):
                cand |= children_in(s, P.pat)
        bmax = 0
        new = []
        for q in cand:
            if q in P.kids:
                continue
            c = self.full_node(q, P)
            if c.sup < self.LB(c, c.sup if self.first else 0):
                bmax = max(bmax, c.sup)       # below LB: folded into b
            elif self.irregular(c):
                c.dead = True
                P.kids[q] = c                 # tombstone, not counted in b
            else:
                P.kids[q] = c
                new.append(c)
        P.b = bmax
        P.anchor_N, P.anchor_b = self.N, P.b
        for c in new:
            self.expand(c)

    def remove_subtree(self, n):
        P = n.parent
        del P.kids[n.pat]
        P.b = max(P.b, n.sup)
        P.anchor_N, P.anchor_b = self.N, P.b

    def live_nodes(self, n=None):
        n = n or self.root
        for c in list(n.kids.values()):
            if not c.dead:
                yield c
                yield from self.live_nodes(c)

    # ---- one batch
    def add_batch(self, batch):
        """batch: list of sequences (each a list of frozensets) in original order."""
        # step 1
        self.nD_old = len(self.DT)
        dT = []
        for s in batch:
            self.N += 1
            if any(x in self.T for its in s for x in its):
                dT.append((self.N, s))
        self.DT.extend(dT)
        self.nD_new = len(dT)
        ms = min_sup_of(self.sigma, self.N)

        if self.first:
            self.root.sup = len(self.DT)
            self.expand(self.root)
            self.first = False
            return

        # step 2: walk the tree on DeltaD (prefix order, parent before child)
        delta = {}            # node -> (sup_delta, rsum, rcnt)
        child_delta = {}      # node -> {q: sup_delta} for untracked children
        pos_end = self.N + 1

        def walk(P, seqs):
            cd = {}
            for p, s in seqs:
                for q in children_in(s, P.pat):
                    if q not in P.kids:
                        cd[q] = cd.get(q, 0) + 1
            child_delta[P] = cd
            for q, c in P.kids.items():
                if c.dead:
                    continue
                sq = [(p, s) for p, s in seqs if contains(s, q)]
                rs = 0.0
                for p, s in sq:
                    c.add_pos(p)
                    rs += math.exp(-self.lam * (pos_end - p))
                delta[c] = (len(sq), rs, len(sq))
                walk(c, sq)

        self.root.sup += len(dT)
        walk(self.root, dT)

        # step 3: kill irregular nodes (kept as tombstones, not folded into b)
        for n in list(self.live_nodes()):
            if not n.dead and self.irregular(n):
                n.dead = True
                n.kids = {}

        # step 4: rescan decision
        rescan = []
        for P in [self.root] + list(self.live_nodes()):
            cd = child_delta.get(P, {})
            cd = {q: v for q, v in cd.items() if q not in P.kids}
            if not cd:
                continue
            if any(P.b + v >= ms for v in cd.values()):
                rescan.append(P)
            else:
                P.b += max(cd.values())

        # step 5: re-check every live node against the new min_sup, drop nodes below LB
        def prune(P):
            for q, c in list(P.kids.items()):
                if c.dead:
                    continue
                sd, rs, rc = delta.get(c, (0, 0.0, 0))
                if c.sup < self.LB(c, sd, rs, rc):
                    self.remove_subtree(c)
                else:
                    prune(c)
        prune(self.root)

        # step 5 (cont.) and 6: rescans, child insertion, DFS from the new children
        for P in rescan:
            if P is not self.root and not self._attached(P):
                continue
            self.rescans += 1
            self.expand(P)

    def _attached(self, n):
        while n.parent is not None:
            if n.pat not in n.parent.kids or n.parent.kids[n.pat] is not n or n.dead:
                return False
            n = n.parent
        return True

    def output(self):
        ms = min_sup_of(self.sigma, self.N)
        return {n.pat for n in self.live_nodes()
                if n.sup >= ms and has_target(n.pat, self.T)
                and max(n.g, self.open_gap(n)) <= self.max_per}

    def check_invariants(self):
        """I2: every untracked child of a live node has sup <= b."""
        for P in [self.root] + list(self.live_nodes()):
            cand = set()
            for p, s in self.DT:
                if contains(s, P.pat):
                    cand |= children_in(s, P.pat)
            for q in cand:
                if q in P.kids:
                    continue
                sup = sum(1 for p, s in self.DT if contains(s, q))
                if sup > P.b:
                    return f"I2 violated at {P.pat}: child {q} sup={sup} > b={P.b}"
        return None
