import random
from fractions import Fraction
from inc_proto import IncMiner, remine
from test_equiv import rand_seq
def run(seed, inv):
    rng=random.Random(10**6+seed)
    n_items=rng.randint(3,7); T=set(rng.sample(range(n_items),rng.randint(1,3)))
    sigma=Fraction(rng.randint(5,50),100); max_per=rng.randint(2,25)
    eta=rng.choice([0,0.3,0.6,0.9]); w=(1.0,rng.choice([0,1.0]),rng.choice([0,1.0]))
    m=IncMiner(sigma,max_per,T,eta,w); DT=[]; N=0
    for bi in range(rng.randint(3,10)):
        batch=[rand_seq(rng,n_items,5,3) for _ in range(rng.randint(0,10))]
        m.add_batch(batch)
        for s in batch:
            N+=1
            if any(x in T for its in s for x in its): DT.append((N,s))
        if remine(DT,N,sigma,max_per,T)!=m.output(): return f"mismatch seed {seed} batch {bi}"
        if inv:
            e=m.check_invariants()
            if e: return f"seed {seed}: {e}"
    return None
bad=0
for sd in range(20000):
    r=run(sd, sd%20==0)
    if r:
        bad+=1
        if bad<=5: print(r)
print("20000 larger cases,",bad,"mismatches")
