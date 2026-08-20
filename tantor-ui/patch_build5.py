import sys, os, re
sys.stdout.reconfigure(encoding='utf-8')
BASE = 'src'

def read(p):
    with open(os.path.join(BASE, p), 'r', encoding='utf-8') as f:
        return f.read()
def write(p, c):
    with open(os.path.join(BASE, p), 'w', encoding='utf-8') as f:
        f.write(c)
def sub(c, old, new, tag=''):
    if old not in c: print(f"  MISS [{tag}]"); return c
    print(f"  OK   [{tag}]"); return c.replace(old, new, 1)
def sub_re(c, pattern, new, tag='', flags=re.DOTALL):
    result, n = re.subn(pattern, new, c, flags=flags)
    if n == 0: print(f"  MISS_RE [{tag}]")
    else: print(f"  OK_RE  [{tag}] ({n}x)")
    return result

print("=== FIX: Sidebar.tsx ===")
c = read('components/Sidebar.tsx')
c = sub_re(c, r"<\(item\.icon as any\) ", "{(item.icon as any)({ ", 'Sidebar fix icon 1')
c = sub_re(c, r"size=\{15\} className=\"nav-item-icon\" />", "size: 15, className: 'nav-item-icon' })}", 'Sidebar fix icon 2')
c = sub_re(c, r"<\(item\.icon as any\) ", "{(item.icon as any)({ ", 'Sidebar fix icon 3')
c = sub_re(c, r"size=\{20\} className=\"nav-item-icon\" />", "size: 20, className: 'nav-item-icon' })}", 'Sidebar fix icon 4')
write('components/Sidebar.tsx', c)
print("=== Done ===")
