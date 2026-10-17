#!/usr/bin/env python3
"""Compare exported JVM descriptors against a pre-refactor class snapshot.

Captures generic Signature, checked Exceptions, class hierarchy and JVM access
flags without loading classes or relying on a third-party bytecode library.
Additional members are allowed; removed descriptors and narrowed contracts fail.
"""
import argparse, json, struct
from pathlib import Path

class Reader:
    def __init__(self, data): self.data, self.pos = data, 0
    def take(self, n):
        result = self.data[self.pos:self.pos+n]; self.pos += n; return result
    def u1(self): return int.from_bytes(self.take(1), 'big')
    def u2(self): return int.from_bytes(self.take(2), 'big')
    def u4(self): return int.from_bytes(self.take(4), 'big')

def read_class(path):
    r = Reader(path.read_bytes())
    if r.u4() != 0xcafebabe: raise ValueError(path)
    minor, major, count = r.u2(), r.u2(), r.u2()
    cp = [None] * count
    i = 1
    while i < count:
        tag = r.u1()
        if tag == 1: cp[i] = r.take(r.u2()).decode('utf-8', 'replace')
        elif tag in (7, 8, 16, 19, 20): cp[i] = r.u2()
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): r.take(4)
        elif tag in (5, 6): r.take(8); i += 1
        elif tag == 15: r.take(3)
        else: raise ValueError((path, tag))
        i += 1
    def classname(index): return cp[cp[index]] if index else None
    def attrs():
        result = {}
        for _ in range(r.u2()):
            name, data = cp[r.u2()], Reader(r.take(r.u4()))
            if name == 'Signature': result[name] = cp[data.u2()]
            elif name == 'Exceptions': result[name] = sorted(classname(data.u2()) for _ in range(data.u2()))
        return result
    flags, name, parent = r.u2(), classname(r.u2()), classname(r.u2())
    interfaces = [classname(r.u2()) for _ in range(r.u2())]
    members = {}
    for kind in ('field', 'method'):
        for _ in range(r.u2()):
            access, member, desc = r.u2(), cp[r.u2()], cp[r.u2()]
            attributes = attrs()
            if access & 5: members[f'{kind} {member} {desc}'] = {'flags': access, **attributes}
    attributes = attrs()
    return name, {'flags': flags, 'major': major, 'super': parent, 'interfaces': interfaces,
                  'members': members, **attributes}

def snapshot(root):
    result = {}
    for p in sorted(Path(root).glob('klib-*/build/classes/**/**/*.class')):
        if '/test/' in str(p) or '/testFixtures/' in str(p): continue
        name, data = read_class(p)
        if data['flags'] & 1: result[name] = data
    return result

def compare(old, new):
    errors = []
    for name, prior in old.items():
        current = new.get(name)
        if current is None: errors.append(f'{name}: exported class removed'); continue
        if current['major'] > 52: errors.append(f'{name}: exceeds Java 8 class version')
        if prior['super'] != current['super']: errors.append(f'{name}: superclass changed')
        if not set(prior['interfaces']) <= set(current['interfaces']): errors.append(f'{name}: interface removed')
        # A previously extensible class cannot become final, nor abstract when concrete.
        if (current['flags'] & 0x410) & ~(prior['flags'] & 0x410): errors.append(f'{name}: class final/abstract contract narrowed')
        for member, before in prior['members'].items():
            after = current['members'].get(member)
            if after is None: errors.append(f'{name}: removed {member}'); continue
            if (before['flags'] & 8) != (after['flags'] & 8): errors.append(f'{name}: static changed: {member}')
            if before['flags'] & 1 and not after['flags'] & 1: errors.append(f'{name}: visibility narrowed: {member}')
            if not prior['flags'] & 0x10 and (after['flags'] & 0x410) & ~(before['flags'] & 0x410): errors.append(f'{name}: final/abstract narrowed: {member}')
            for attr in ('Signature', 'Exceptions'):
                if before.get(attr) != after.get(attr): errors.append(f'{name}: {attr} changed: {member}: {before.get(attr)} -> {after.get(attr)}')
    return errors

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('root'); parser.add_argument('--save'); parser.add_argument('--baseline')
    args = parser.parse_args(); current = snapshot(args.root)
    if args.save: Path(args.save).write_text(json.dumps(current, indent=2, sort_keys=True) + '\n')
    if args.baseline:
        errors = compare(json.loads(Path(args.baseline).read_text()), current)
        print('\n'.join(errors)); print(f'{len(current)} exported classes; {len(errors)} incompatibilities')
        raise SystemExit(bool(errors))
    print(f'{len(current)} exported classes captured')
