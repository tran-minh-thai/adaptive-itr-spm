import random, subprocess, sys
import os; HERE=os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0,os.path.join(HERE,'prototype'))
from fractions import Fraction
from inc_proto import IncMiner, remine
from test_equiv import rand_seq
def pstr(p): return "|".join(" ".join(str(x) for x in its) for its in p)
def spmf(seq): return " ".join(" ".join(str(x) for x in sorted(its))+" -1" for its in seq)+" -2"
import tempfile; _TD=tempfile.mkdtemp(); CASES=os.path.join(_TD,'cases.txt'); JOUT=os.path.join(_TD,'jout.txt')
n=int(sys.argv[1]); base=int(sys.argv[2]) if len(sys.argv)>2 else 0
cases=[]; exp=[]
for sd in range(base,base+n):
    rng=random.Random(5000+sd)
    ni=rng.randint(2,7); T=sorted(rng.sample(range(ni),rng.randint(1,2)))
    sig=Fraction(rng.randint(1,60),100); mp=rng.randint(1,20)
    eta=rng.choice([0,0.1,0.3,0.5,0.7,0.9]); lam=rng.choice([0.1,0.5,0.9])
    w=tuple(rng.choice([0.0,0.5,1.0]) for _ in range(3))
    if sum(w)==0: w=(1.0,1.0,1.0)
    batches=[[rand_seq(rng,ni,5,min(3,ni)) for _ in range(rng.randint(0,8))] for _ in range(rng.randint(2,7))]
    sizes=[len(b) for b in batches]
    if sum(sizes)==0: continue
    dec=f"{sig.numerator/sig.denominator:.2f}"; assert Fraction(dec)==sig
    cases.append(f"CASE {','.join(map(str,sizes))} {dec} {mp} {','.join(map(str,T))} {eta} {lam} {w[0]} {w[1]} {w[2]}")
    for b in batches:
        for s in b: cases.append(spmf(s))
    m=IncMiner(sig,mp,set(T),eta,w,lam=lam); DT=[]; N=0; lines=[]
    for bi,b in enumerate(batches):
        m.add_batch(b)
        for s in b:
            N+=1
            if any(x in T for its in s for x in its): DT.append((N,s))
        o=m.output(); r=remine(DT,N,sig,mp,set(T))
        assert o==r, ("prototype differs from remine", sd)
        lines.append("B%d %s"%(bi,",".join(sorted(pstr(p) for p in o))))
    lines.append("RESCANS %d"%m.rescans)
    exp.append((sd,lines))
open(CASES,'w').write("\n".join(cases)+"\n")
subprocess.run(["java"]+os.environ.get("INC_JOPTS","").split()+["-cp",os.environ.get("INC_CP","../../target/classes"),"com.adaptive.itr.spm.IncEquivMulti",CASES,JOUT],check=True,stderr=subprocess.DEVNULL)
got=open(JOUT).read().split("\n")
i=0; badset=0; badres=0; tot=0
for sd,lines in exp:
    g=got[i:i+len(lines)]; i+=len(lines)
    tot+=1
    if g[:-1]!=lines[:-1]:
        badset+=1
        if badset<=3:
            for a,b in zip(lines,g):
                if a!=b: print("seed",sd,"\n PY:",a[:200],"\n JV:",b[:200]); break
    if g[-1]!=lines[-1]: badres+=1
print(f"{tot} cases: pattern-set mismatches {badset}, rescan-count mismatches {badres}")
