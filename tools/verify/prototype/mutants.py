import sys, importlib, inc_proto as ip, test_equiv
from test_equiv import run
def trial(name, patch):
    importlib.reload(ip); patch(ip); importlib.reload(test_equiv)
    test_equiv.IncMiner = ip.IncMiner
    bad=sum(1 for sd in range(300) if test_equiv.run(sd))
    print(f"mutant {name}: caught in {bad}/300 cases")
src=open('inc_proto.py').read()
def m1(ip):   # no rescan: always just add to b
    s=src.replace("if any(P.b + v >= ms for v in cd.values()):","if False:")
    exec(compile(s,'m1','exec'),ip.__dict__)
def m2(ip):   # do not fold the support of a dropped node into b
    s=src.replace("P.b = max(P.b, n.sup)","pass")
    exec(compile(s,'m2','exec'),ip.__dict__)
def m3(ip):   # do not kill on the open gap (closed gap g only)
    s=src.replace("return n.sup == 0 or max(n.g, self.open_gap(n)) > self.max_per","return n.sup == 0 or n.g > self.max_per or (self.open_gap(n) > self.max_per and False)")
    s=s.replace("and max(n.g, self.open_gap(n)) <= self.max_per}","}")
    exec(compile(s,'m3','exec'),ip.__dict__)
for nm,f in [("no rescan step",m1),("dropped node support not folded into b",m2),("no open-gap check",m3)]:
    trial(nm,f)
