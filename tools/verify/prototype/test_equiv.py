"""Compares the pattern sets of the incremental version and a re-mine from scratch after every batch, on small random data."""
import random, sys
from fractions import Fraction
from inc_proto import IncMiner, remine

def rand_seq(rng, n_items, max_len, max_its):
    L = rng.randint(1, max_len)
    return [frozenset(rng.sample(range(n_items), rng.randint(1, max_its))) for _ in range(L)]

def run(seed, check_inv=False):
    rng = random.Random(seed)
    n_items = rng.randint(2, 5)
    T = set(rng.sample(range(n_items), rng.randint(1, 2)))
    sigma = Fraction(rng.randint(1, 60), 100)
    max_per = rng.randint(1, 12)
    eta = rng.choice([0, 0.1, 0.3, 0.5, 0.7, 0.9])
    w = tuple(rng.choice([0.0, 0.5, 1.0]) for _ in range(3))
    if sum(w) == 0:
        w = (1.0, 1.0, 1.0)
    m = IncMiner(sigma, max_per, T, eta, w, lam=rng.choice([0.1, 0.5, 0.9]))
    DT = []
    N = 0
    for bi in range(rng.randint(2, 6)):
        batch = [rand_seq(rng, n_items, 4, 2) for _ in range(rng.randint(0, 6))]
        m.add_batch(batch)
        for s in batch:
            N += 1
            if any(x in T for its in s for x in its):
                DT.append((N, s))
        ref = remine(DT, N, sigma, max_per, T)
        got = m.output()
        if ref != got:
            return f"seed={seed} batch={bi} missing={sorted(ref-got)[:3]} extra={sorted(got-ref)[:3]}"
        if check_inv:
            e = m.check_invariants()
            if e:
                return f"seed={seed} batch={bi} {e}"
    return None

if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 2000
    bad = 0
    for sd in range(n):
        r = run(sd, check_inv=(sd % 10 == 0))
        if r:
            bad += 1
            if bad <= 5:
                print(r)
    print(f"{n} cases, {bad} mismatches")
