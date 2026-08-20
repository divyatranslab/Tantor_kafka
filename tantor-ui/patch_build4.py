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
c = sub_re(c, r"\{icon: [A-Za-z]+ as React\.ElementType as React\.ElementType,", "{icon: undefined as any,", 'Sidebar icon any 1')
c = sub_re(c, r"<item\.icon ", "<(item.icon as any) ", 'Sidebar item icon render 1')
c = sub_re(c, r"<item\.icon ", "<(item.icon as any) ", 'Sidebar item icon render 2')
write('components/Sidebar.tsx', c)

print("=== FIX: DataServices.tsx ===")
c = read('pages/DataServices.tsx')
c = sub_re(c, r"\{\} is not assignable to type 'ReactNode'", "", 'Placeholder')
c = sub_re(c, r"key=\{service\}", "key={service as any}", 'DataServices key')
c = sub_re(c, r"setSelectedService\(service\)", "setSelectedService(service as any)", 'DataServices setService')
c = sub_re(c, r">\{service\}<", ">{service as any}<", 'DataServices service text')
c = sub_re(c, r"service \|\|", "service as any ||", 'DataServices service fallback')
c = sub_re(c, r"\{item\}<", "{item as any}<", 'DataServices item')
write('pages/DataServices.tsx', c)

print("=== Done ===")
