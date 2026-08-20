import sys, os, re
sys.stdout.reconfigure(encoding='utf-8')
BASE = 'src'

def read(p):
    with open(os.path.join(BASE, p), 'r', encoding='utf-8') as f:
        return f.read()
def write(p, c):
    with open(os.path.join(BASE, p), 'w', encoding='utf-8') as f:
        f.write(c)
def create(p, c):
    write(p, c); print(f"  CREATED {p}")
def sub(c, old, new, tag=''):
    if old not in c:
        print(f"  MISS [{tag}]")
        return c
    print(f"  OK   [{tag}]")
    return c.replace(old, new, 1)

print("=== FIX: ConfirmDialog.tsx — remove confirmAction/notifyAction from file ===")
c = read('components/ConfirmDialog.tsx')
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    if 1 <= i <= 45:
        print(f"  CD L{i}: {line.rstrip()}")
write('components/ConfirmDialog.tsx', c)
