import json, re
from html.parser import HTMLParser

SRC = 'docs/instrukcja-dla-osob-testujacych.html'
html = open(SRC, encoding='utf-8').read()
html = re.sub(r'<style.*?</style>', '', html, flags=re.S)

class Node:
    def __init__(self, tag, attrs):
        self.tag, self.cls, self.kids = tag, (dict(attrs).get('class') or ''), []
    def cl(self, c): return c in self.cls.split()

class Tree(HTMLParser):
    VOID = {'meta','link','br','hr','img','input'}
    def __init__(self):
        super().__init__(); self.root = Node('root', []); self.stack = [self.root]
    def handle_starttag(self, tag, attrs):
        if tag in self.VOID: return
        n = Node(tag, attrs); self.stack[-1].kids.append(n); self.stack.append(n)
    def handle_endtag(self, tag):
        for i in range(len(self.stack)-1, 0, -1):
            if self.stack[i].tag == tag:
                del self.stack[i:]; return
    def handle_data(self, data):
        if data.strip(): self.stack[-1].kids.append(data)

t = Tree(); t.feed(html)

def find(node, pred, out=None):
    out = [] if out is None else out
    for k in node.kids:
        if isinstance(k, Node):
            if pred(k): out.append(k)
            find(k, pred, out)
    return out

def runs(node, bold=False, italic=False, mono=False):
    """Zamienia drzewo w listę fragmentów z formatowaniem."""
    out = []
    for k in node.kids:
        if isinstance(k, str):
            out.append({'t': re.sub(r'\s+', ' ', k), 'b': bold, 'i': italic, 'm': mono})
        else:
            out += runs(k, bold or k.tag in ('strong','b'),
                           italic or k.tag in ('em','i'),
                           mono or k.cl('say'))
    return out

def text(node): return ''.join(r['t'] for r in runs(node)).strip()

body = find(t.root, lambda n: n.tag == 'body')[0]
doc = {'sections': []}

mast = find(body, lambda n: n.cl('masthead'))[0]
doc['kicker'] = text(find(mast, lambda n: n.cl('kicker'))[0])
doc['title']  = text(find(mast, lambda n: n.tag == 'h1')[0])
doc['intro']  = [runs(p) for p in find(mast, lambda n: n.cl('intro'))]

def parse_box(b):
    head = find(b, lambda n: n.cl('head'))
    kind = 'stop' if b.cl('stop') else 'warn' if b.cl('warn') else 'plain'
    return {'type': 'box', 'kind': kind,
            'head': text(head[0]) if head else None,
            'paras': [runs(p) for p in find(b, lambda n: n.tag == 'p')]}

# ramka przed sekcjami
for b in body.kids:
    if isinstance(b, Node) and b.tag == 'div' and b.cl('wrap'):
        wrap = b
for ch in wrap.kids:
    if isinstance(ch, Node) and ch.cl('box'):
        doc['lead_box'] = parse_box(ch); break

def parse_section(sec):
    out = {'num': text(find(sec, lambda n: n.cl('sec-num'))[0]),
           'title': text(find(sec, lambda n: n.tag == 'h2')[0]),
           'blocks': []}
    for ch in sec.kids:
        if not isinstance(ch, Node) or ch.cl('sec-head'): continue
        if ch.tag == 'p':
            out['blocks'].append({'type': 'p', 'runs': runs(ch)})
        elif ch.cl('steps'):
            out['blocks'].append({'type': 'steps', 'items': [
                {'what': text(find(li, lambda n: n.cl('what'))[0]),
                 'how':  text(find(li, lambda n: n.cl('how'))[0])}
                for li in find(ch, lambda n: n.tag == 'li')]})
        elif ch.cl('gestures'):
            out['blocks'].append({'type': 'gestures', 'items': [
                {'move': text(find(g, lambda n: n.cl('move'))[0]),
                 'does': text(find(g, lambda n: n.cl('does'))[0]),
                 'note': text(find(g, lambda n: n.cl('note'))[0])}
                for g in find(ch, lambda n: n.cl('gesture'))]})
        elif ch.cl('topic'):
            desc = find(ch, lambda n: n.cl('desc'))
            out['blocks'].append({'type': 'topic',
                'label': text(find(ch, lambda n: n.cl('label'))[0]),
                'desc': text(desc[0]) if desc else None,
                'says': [text(s) for s in find(ch, lambda n: n.cl('say'))]})
        elif ch.cl('box'):
            out['blocks'].append(parse_box(ch))
        elif ch.cl('table-scroll'):
            tab = find(ch, lambda n: n.tag == 'table')[0]
            rows = [[text(c) for c in find(tr, lambda n: n.tag in ('td','th'))]
                    for tr in find(tab, lambda n: n.tag == 'tr')]
            rows = [r for r in rows if r]
            out['blocks'].append({'type': 'table', 'head': rows[0], 'rows': rows[1:]})
        elif ch.tag == 'ul':
            out['blocks'].append({'type': 'ul', 'items': [
                runs(li) for li in find(ch, lambda n: n.tag == 'li')]})
        elif ch.tag == 'h3':
            out['blocks'].append({'type': 'h3', 'text': text(ch)})
    return out

doc['sections'] = [parse_section(s) for s in find(wrap, lambda n: n.tag == 'section')]
doc['closing'] = text(find(wrap, lambda n: n.cl('closing'))[0])

json.dump(doc, open('/tmp/claude-0/manual.json', 'w', encoding='utf-8'),
          ensure_ascii=False, indent=1)

print('sekcje:', len(doc['sections']))
for s in doc['sections']:
    kinds = {}
    for b in s['blocks']: kinds[b['type']] = kinds.get(b['type'], 0) + 1
    print(f"  {s['num']} {s['title']:34} {kinds}")
