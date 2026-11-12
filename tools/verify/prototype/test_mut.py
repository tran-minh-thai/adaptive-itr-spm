import random, sys
import inc_proto as ip
from test_equiv import run

# non-emptiness statistics of the outputs (to show the comparisons are not vacuous)
import test_equiv
tot_out=0; tot_res=0; nonempty=0
orig_add=ip.IncMiner.add_batch
for sd in range(300):
    rng=random.Random(sd)
r=0
class Stat: pass
def wrapped(self,batch):
    orig_add(self,batch)
ip.IncMiner.add_batch=wrapped
cnt={'out':0,'nonempty':0,'res':0}
orig_output=ip.IncMiner.output
def out2(self):
    o=orig_output(self); cnt['out']+=len(o); cnt['nonempty']+= (1 if o else 0); return o
ip.IncMiner.output=out2
for sd in range(300): run(sd)
print("output patterns summed over batches:",cnt['out'],"| non-empty outputs:",cnt['nonempty'])
ip.IncMiner.output=orig_output
