import sys, json, zipfile, re
import xml.etree.ElementTree as ET

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
path = sys.argv[1]
z = zipfile.ZipFile(path)
problems, notes = [], []

# 1. Każda część XML musi się parsować.
parts = {}
for n in z.namelist():
    if n.endswith('.xml') or n.endswith('.rels'):
        try:
            parts[n] = ET.fromstring(z.read(n))
        except ET.ParseError as e:
            problems.append(f'{n}: niepoprawny XML - {e}')
notes.append(f'części XML sparsowanych: {len(parts)}')

doc = parts['word/document.xml']

# 2. Cała treść w kolejności dokumentu.
texts = [(e.text or '') for e in doc.iter(W+'t')]
flat = ''.join(texts)
notes.append(f'elementów tekstowych: {len(texts)}, znaków: {len(flat)}')

# 3. Żadnego znaku nowej linii ani wstawionego ręcznie punktora w treści.
if '\n' in flat: problems.append('znak nowej linii wewnątrz w:t')
for bad in ('•', '●', '- '):
    if bad == '- ' : continue
    if any(t.strip() == bad for t in texts):
        problems.append(f'punktor {bad!r} wpisany jako tekst zamiast numeracji')

# 4. Cieniowanie: val="solid" renderuje się na czarno.
for sh in doc.iter(W+'shd'):
    if sh.get(W+'val') == 'solid':
        problems.append('w:shd val="solid" - renderuje się czarnym')

# 5. Tabele: siatka, liczba kolumn, szerokości w dxa.
for ti, tbl in enumerate(doc.iter(W+'tbl'), 1):
    grid = tbl.find(W+'tblGrid')
    if grid is None:
        problems.append(f'tabela {ti}: brak tblGrid'); continue
    cols = [int(g.get(W+'w')) for g in grid.findall(W+'gridCol')]
    rows = tbl.findall(W+'tr')
    for ri, tr in enumerate(rows, 1):
        cells = tr.findall(W+'tc')
        if len(cells) != len(cols):
            problems.append(f'tabela {ti} wiersz {ri}: {len(cells)} komórek przy {len(cols)} kolumnach')
        widths = []
        for tc in cells:
            wEl = tc.find(f'{W}tcPr/{W}tcW')
            if wEl is None or wEl.get(W+'type') != 'dxa':
                problems.append(f'tabela {ti} wiersz {ri}: szerokość komórki nie w dxa')
            else:
                widths.append(int(wEl.get(W+'w')))
        if widths and abs(sum(widths) - sum(cols)) > 2:
            problems.append(f'tabela {ti} wiersz {ri}: suma komórek {sum(widths)} != siatka {sum(cols)}')
    notes.append(f'tabela {ti}: {len(rows)} wierszy x {len(cols)} kol., szerokość {sum(cols)} dxa')

# 6. Numeracja: każdy numId użyty w dokumencie musi istnieć i mieć abstractNum.
used = {p.get(W+'val') for p in doc.iter(W+'numId') if p.get(W+'val')}
numbering = parts.get('word/numbering.xml')
if used and numbering is None:
    problems.append('dokument używa numeracji, brak word/numbering.xml')
elif used:
    defined = {}
    for num in numbering.findall(W+'num'):
        a = num.find(W+'abstractNumId')
        defined[num.get(W+'numId')] = a.get(W+'val') if a is not None else None
    absids = {a.get(W+'abstractNumId') for a in numbering.findall(W+'abstractNum')}
    for u in sorted(used):
        if u not in defined: problems.append(f'numId {u} użyty, ale niezdefiniowany')
        elif defined[u] not in absids: problems.append(f'numId {u} wskazuje na nieistniejący abstractNum')
    notes.append(f'numeracje: użyte {sorted(used)}, zdefiniowane {sorted(defined)}')

# 7. Content types muszą pokrywać każdą część.
ct = parts['[Content_Types].xml']
CT = '{http://schemas.openxmlformats.org/package/2006/content-types}'
defaults = {d.get('Extension').lower() for d in ct.findall(CT+'Default')}
overrides = {o.get('PartName') for o in ct.findall(CT+'Override')}
for n in z.namelist():
    if n.endswith('/'): continue
    ext = n.rsplit('.', 1)[-1].lower()
    if '/' + n not in overrides and ext not in defaults:
        problems.append(f'{n}: brak typu zawartości')

# 8. Relacje muszą wskazywać na istniejące pliki.
for rels_name, root in parts.items():
    if not rels_name.endswith('.rels'): continue
    base = rels_name.rsplit('_rels/', 1)[0]
    for r in root:
        if r.get('TargetMode') == 'External': continue
        tgt = r.get('Target')
        cand = (base + tgt).replace('/./', '/')
        while '/../' in cand:
            cand = re.sub(r'[^/]+/\.\./', '', cand, count=1)
        if cand not in z.namelist():
            problems.append(f'{rels_name}: relacja wskazuje na brakujący {cand}')

# 9. Porównanie z materiałem źródłowym - nic nie mogło wypaść.
src = json.load(open('/tmp/claude-0/manual.json', encoding='utf-8'))
def norm(s): return re.sub(r'\s+', ' ', s).strip()
hay = norm(flat)
expected = [src['title'], src['kicker'], src['closing']]
for s in src['sections']:
    expected.append(s['title'])
    for b in s['blocks']:
        if b['type'] == 'p': expected.append(''.join(r['t'] for r in b['runs']))
        elif b['type'] == 'steps':
            for it in b['items']: expected += [it['what'], it['how']]
        elif b['type'] == 'gestures':
            for g in b['items']: expected += [g['move'], g['does'], g['note']]
        elif b['type'] == 'topic':
            expected.append(b['label'])
            if b['desc']: expected.append(b['desc'])
            expected += b['says']
        elif b['type'] == 'box':
            if b['head']: expected.append(b['head'])
            expected += [''.join(r['t'] for r in p) for p in b['paras']]
        elif b['type'] == 'table':
            expected += b['head'] + [c for r in b['rows'] for c in r]
        elif b['type'] == 'ul':
            expected += [''.join(r['t'] for r in it) for it in b['items']]
missing = [e for e in expected if norm(e) and norm(e) not in hay]
notes.append(f'fragmentów treści sprawdzonych: {len(expected)}, brakujących: {len(missing)}')
for m in missing[:8]: problems.append(f'BRAK w dokumencie: {norm(m)[:80]!r}')

print('--- ustalenia ---')
for n in notes: print(' ', n)
print('--- problemy ---')
if problems:
    for p in problems: print('  ✗', p)
    sys.exit(1)
print('  (brak)')
