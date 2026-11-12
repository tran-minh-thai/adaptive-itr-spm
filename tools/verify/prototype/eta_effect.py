import random
from fractions import Fraction
from inc_proto import IncMiner
from test_equiv import rand_seq
rng=random.Random(7)
data=[[rand_seq(rng,6,5,2) for _ in range(40)] for _ in range(12)]
T={0,1}
print("eta | rescans | live nodes at the end | output patterns")
for eta in [0,0.1,0.3,0.5,0.7,0.9]:
    m=IncMiner(Fraction(15,100),40,T,eta,(1.0,1.0,1.0))
    for b in data: m.add_batch(b)
    print(f"{eta:3} | {m.rescans:8} | {sum(1 for _ in m.live_nodes()):16} | {len(m.output())}")
