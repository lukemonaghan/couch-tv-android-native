#!/usr/bin/env python3
"""Makes app/src/main/assets/presets/index.tsv from a checkout of projectM-visualizer/presets-cream-of-the-crop.
Usage: make-preset-index.py <checkout dir> <favourites.txt, one 'Category/file.milk' per line>
Lines: category <TAB> group <TAB> file name <TAB> bytes (the pack is Category/Group/file.milk; "! Transition" is left out:
those are fades to black, not visuals). starter.txt lists the first-run pack as Category/Group/file.milk."""
import os, sys
src, fav = sys.argv[1], sys.argv[2]
out = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'presets')
rows = []
for cat in sorted(os.listdir(src)):
    d = os.path.join(src, cat)
    if not os.path.isdir(d) or cat.startswith('.') or cat.startswith('!'): continue
    for grp in sorted(os.listdir(d)):
        gd = os.path.join(d, grp)
        if not os.path.isdir(gd): continue
        for f in sorted(os.listdir(gd)):
            if f.endswith('.milk'): rows.append((cat, grp, f, os.path.getsize(os.path.join(gd, f))))
os.makedirs(out, exist_ok=True)
with open(os.path.join(out, 'index.tsv'), 'w') as o:
    for r in rows: o.write('%s\t%s\t%s\t%d\n' % r)
known = {'/'.join(r[:3]) for r in rows}
starter = [l.rstrip('\n') for l in open(fav) if l.strip()]
starter = [s for s in starter if s in known]
with open(os.path.join(out, 'starter.txt'), 'w') as o: o.write('\n'.join(starter) + '\n')
print(len(rows), 'presets,', len(starter), 'in the starter pack,', sum(r[3] for r in rows) // 1024, 'KB in all')
