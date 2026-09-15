const fs = require('fs');
const D = require('/tmp/claude-0/node_modules/docx');
const {
  Document, Packer, Paragraph, TextRun, HeadingLevel, AlignmentType,
  Table, TableRow, TableCell, WidthType, ShadingType, BorderStyle,
  LevelFormat, PageOrientation
} = D;

const doc_ = JSON.parse(fs.readFileSync('/tmp/claude-0/manual.json', 'utf8'));

// === Paleta marki V.I.C.T.O.R. ===
// Cyjan i czerń wprost z logo. Do TEKSTU idzie cyjan przyciemniony: markowy
// 00A0E3 na bieli daje circa 2,7:1 przy wymaganych 4,5:1. Pełny cyjan zostaje
// do wypełnień i kresek, gdzie kontrast liczy się inaczej.
const INK = '2B2A29';
const CYAN = '00A0E3';
const CYAN_DEEP = '00719F';
const MUTED = '5B615F';
const SUNK = 'F2F3F1';
const RULE = 'D8DCDA';
const WARN_BG = 'FBF0DC';
const WARN_INK = '8A5100';
const STOP_BG = 'FBEAE8';
const STOP_INK = '9B2019';

const SANS = 'Calibri';
const MONO = 'Consolas';

// A4 (11906 dxa) minus marginesy 2 cm z każdej strony.
const CONTENT = 11906 - 2 * 1134;

const inline = (runs, opts = {}) => runs.map(r => new TextRun({
  text: r.t,
  bold: r.b || opts.bold,
  italics: r.i,
  font: r.m ? MONO : undefined,
  size: r.m ? 19 : opts.size,
  color: r.m ? CYAN_DEEP : opts.color
}));

const para = (runs, o = {}) => new Paragraph({
  children: Array.isArray(runs) ? runs : [runs],
  spacing: { before: o.before ?? 0, after: o.after ?? 140, line: 276 },
  ...o.rest
});

const txt = (t, o = {}) => new TextRun({
  text: t, bold: o.bold, italics: o.italics, color: o.color,
  font: o.font, size: o.size, allCaps: o.caps, characterSpacing: o.track
});

/** Blok "powiedz to" - jeden wiersz w szarym polu, monospace. */
const sayLine = (t, first, last) => new Paragraph({
  children: [txt('„' + t + '”', { font: MONO, size: 19, color: INK })],
  shading: { type: ShadingType.CLEAR, fill: SUNK, color: 'auto' },
  spacing: { before: first ? 40 : 0, after: last ? 140 : 0, line: 264 },
  indent: { left: 170, right: 170 },
  border: {
    left: { style: BorderStyle.SINGLE, size: 12, color: CYAN, space: 6 }
  }
});

/** Ramka ostrzegawcza/informacyjna. */
function box(b) {
  const bg = b.kind === 'warn' ? WARN_BG : b.kind === 'stop' ? STOP_BG : SUNK;
  const headColor = b.kind === 'warn' ? WARN_INK : b.kind === 'stop' ? STOP_INK : CYAN_DEEP;
  const out = [];
  const shade = { type: ShadingType.CLEAR, fill: bg, color: 'auto' };
  if (b.head) out.push(new Paragraph({
    children: [txt(b.head, { bold: true, color: headColor })],
    shading: shade, spacing: { before: 200, after: 60, line: 276 },
    indent: { left: 170, right: 170 }
  }));
  b.paras.forEach((p, i) => out.push(new Paragraph({
    children: inline(p),
    shading: shade,
    spacing: { before: 0, after: i === b.paras.length - 1 ? 220 : 80, line: 276 },
    indent: { left: 170, right: 170 }
  })));
  return out;
}

const cell = (children, width, o = {}) => new TableCell({
  children, width: { size: width, type: WidthType.DXA },
  shading: o.fill ? { type: ShadingType.CLEAR, fill: o.fill, color: 'auto' } : undefined,
  margins: { top: 90, bottom: 90, left: 130, right: 130 },
  verticalAlign: o.valign
});

const noBorders = {
  top: { style: BorderStyle.NONE, size: 0, color: 'auto' },
  bottom: { style: BorderStyle.NONE, size: 0, color: 'auto' },
  left: { style: BorderStyle.NONE, size: 0, color: 'auto' },
  right: { style: BorderStyle.NONE, size: 0, color: 'auto' },
  insideHorizontal: { style: BorderStyle.SINGLE, size: 4, color: RULE },
  insideVertical: { style: BorderStyle.NONE, size: 0, color: 'auto' }
};

/** Tabela gestów: gest | co robi + wyjaśnienie. */
function gestures(items) {
  const w = [2400, CONTENT - 2400];
  return [new Table({
    columnWidths: w,
    width: { size: CONTENT, type: WidthType.DXA },
    borders: noBorders,
    rows: items.map(g => new TableRow({
      children: [
        cell([new Paragraph({
          children: [txt(g.move, { font: MONO, size: 18, color: CYAN_DEEP, bold: true, caps: true })],
          spacing: { after: 0, line: 264 }
        })], w[0], { valign: 'center' }),
        cell([
          new Paragraph({ children: [txt(g.does, { bold: true })], spacing: { after: 30, line: 264 } }),
          new Paragraph({ children: [txt(g.note, { color: MUTED, size: 19 })], spacing: { after: 0, line: 264 } })
        ], w[1])
      ]
    }))
  }), para([txt('')], { after: 60 })];
}

/** Tabela usterek. */
function troubleTable(b) {
  const w = [Math.round(CONTENT * 0.38), CONTENT - Math.round(CONTENT * 0.38)];
  const rows = [new TableRow({
    tableHeader: true,
    children: b.head.map((h, i) => cell([new Paragraph({
      children: [txt(h, { bold: true, caps: true, size: 17, color: MUTED, track: 12 })],
      spacing: { after: 0, line: 264 }
    })], w[i], { fill: SUNK }))
  })];
  b.rows.forEach(r => rows.push(new TableRow({
    children: r.map((c, i) => cell([new Paragraph({
      children: [txt(c, i === 0 ? { bold: true } : {})],
      spacing: { after: 0, line: 264 }
    })], w[i]))
  })));
  return [new Table({
    columnWidths: w, width: { size: CONTENT, type: WidthType.DXA },
    borders: {
      ...noBorders,
      top: { style: BorderStyle.SINGLE, size: 4, color: RULE },
      bottom: { style: BorderStyle.SINGLE, size: 4, color: RULE }
    },
    rows
  }), para([txt('')], { after: 60 })];
}

// === Składanie dokumentu ===
const body = [];

body.push(new Paragraph({
  children: [txt(doc_.kicker, { bold: true, size: 17, color: CYAN_DEEP, track: 26, caps: true })],
  spacing: { after: 100 }
}));
body.push(new Paragraph({
  children: [txt(doc_.title, { bold: true, size: 56, color: INK })],
  spacing: { after: 160, line: 260 },
  border: { bottom: { style: BorderStyle.SINGLE, size: 18, color: CYAN, space: 8 } }
}));
doc_.intro.forEach(p => body.push(para(inline(p, { size: 23 }), { before: 80, after: 100 })));

box(doc_.lead_box).forEach(p => body.push(p));

for (const sec of doc_.sections) {
  body.push(new Paragraph({
    heading: HeadingLevel.HEADING_1,
    children: [
      txt(sec.num + '   ', { font: MONO, size: 22, color: CYAN_DEEP, bold: true }),
      txt(sec.title, { bold: true, size: 30, color: INK })
    ],
    spacing: { before: 420, after: 160, line: 276 },
    border: { bottom: { style: BorderStyle.SINGLE, size: 10, color: INK, space: 6 } },
    keepNext: true
  }));

  for (const b of sec.blocks) {
    if (b.type === 'p') body.push(para(inline(b.runs)));
    else if (b.type === 'h3') body.push(para([txt(b.text, { bold: true, size: 23 })], { before: 200, after: 80 }));
    else if (b.type === 'steps') {
      b.items.forEach(it => {
        body.push(new Paragraph({
          numbering: { reference: 'kroki', level: 0 },
          children: [txt(it.what, { bold: true })],
          spacing: { before: 120, after: 20, line: 264 }, keepNext: true
        }));
        body.push(new Paragraph({
          children: [txt(it.how, { color: MUTED, size: 20 })],
          indent: { left: 460 }, spacing: { after: 0, line: 264 }
        }));
      });
      body.push(para([txt('')], { after: 60 }));
    }
    else if (b.type === 'gestures') gestures(b.items).forEach(p => body.push(p));
    else if (b.type === 'topic') {
      body.push(para([txt(b.label, { bold: true, size: 23 })], { before: 240, after: b.desc ? 20 : 60 }));
      if (b.desc) body.push(para([txt(b.desc, { color: MUTED, size: 20 })], { after: 60 }));
      b.says.forEach((s, i) => body.push(sayLine(s, i === 0, i === b.says.length - 1)));
    }
    else if (b.type === 'box') box(b).forEach(p => body.push(p));
    else if (b.type === 'table') troubleTable(b).forEach(p => body.push(p));
    else if (b.type === 'ul') {
      b.items.forEach(it => body.push(new Paragraph({
        numbering: { reference: 'punkty', level: 0 },
        children: inline(it),
        spacing: { before: 60, after: 60, line: 276 }
      })));
    }
  }
}

body.push(new Paragraph({
  children: [txt(doc_.closing, { color: MUTED, size: 19 })],
  spacing: { before: 520, after: 0, line: 276 },
  border: { top: { style: BorderStyle.SINGLE, size: 12, color: INK, space: 10 } }
}));

const document = new Document({
  creator: 'V.I.C.T.O.R.',
  title: 'Okulary V.I.C.T.O.R. — instrukcja dla osób testujących',
  description: 'Instrukcja obsługi okularów V.I.C.T.O.R. dla osób testujących sprzęt',
  styles: {
    default: {
      document: { run: { font: SANS, size: 21, color: INK }, paragraph: { spacing: { line: 276 } } }
    }
  },
  numbering: {
    config: [
      {
        reference: 'kroki',
        levels: [{
          level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.START,
          style: { paragraph: { indent: { left: 460, hanging: 320 } },
                   run: { bold: true, color: CYAN_DEEP } }
        }]
      },
      {
        reference: 'punkty',
        levels: [{
          level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.START,
          style: { paragraph: { indent: { left: 400, hanging: 260 } },
                   run: { color: CYAN_DEEP } }
        }]
      }
    ]
  },
  sections: [{
    properties: {
      page: {
        size: { orientation: PageOrientation.PORTRAIT },
        margin: { top: 1247, bottom: 1134, left: 1134, right: 1134 }
      }
    },
    children: body
  }]
});

Packer.toBuffer(document).then(buf => {
  fs.writeFileSync(process.argv[2], buf);
  console.log('zapisano', process.argv[2], (buf.length / 1024).toFixed(1) + ' kB');
});
