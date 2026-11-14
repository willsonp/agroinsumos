import { strToU8, zipSync } from 'fflate';
import { descargar } from './http';

export type TipoColumna = 'texto' | 'numero' | 'moneda' | 'fecha';

export interface ColumnaExcel {
  titulo: string;
  tipo?: TipoColumna;
  /** Ancho aproximado en caracteres. */
  ancho?: number;
}

export interface HojaExcel {
  /** Nombre del archivo (sin extensión). */
  archivo: string;
  /** Título que va en la primera fila (p. ej. "Antigüedad de saldos con suplidores"). */
  titulo: string;
  /** Líneas debajo del título: empresa, período, filtros. */
  subtitulos?: string[];
  columnas: ColumnaExcel[];
  filas: unknown[][];
  /** Fila de totales (en negrita); null en las columnas sin total. */
  totales?: unknown[];
}

/** Texto → bytes en un Uint8Array del entorno actual (fflate distingue archivos de carpetas con instanceof). */
const bytes = (s: string) => new Uint8Array(strToU8(s));

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

function columna(i: number): string {
  let s = '';
  for (let n = i + 1; n > 0; n = Math.floor((n - 1) / 26)) s = String.fromCharCode(65 + ((n - 1) % 26)) + s;
  return s;
}

/** Excel guarda las fechas como días desde 1899-12-30. "2026-11-13" → 46339. */
function serialFecha(v: string): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(v);
  if (!m) return null;
  return Math.round((Date.UTC(+m[1], +m[2] - 1, +m[3]) - Date.UTC(1899, 11, 30)) / 86400000);
}

// Estilos (cellXfs): 0 normal, 1 negrita, 2 moneda #,##0.00, 3 número (General), 4 fecha, 5 moneda negrita, 6 número negrita, 7 título
const ESTILO: Record<TipoColumna, number> = { texto: 0, moneda: 2, numero: 3, fecha: 4 };
const ESTILO_TOTAL: Record<TipoColumna, number> = { texto: 1, moneda: 5, numero: 6, fecha: 1 };

function celda(ref: string, v: unknown, tipo: TipoColumna, estilo: number): string {
  if (v === null || v === undefined || v === '') return `<c r="${ref}" s="${estilo}"/>`;
  if (tipo === 'fecha' && typeof v === 'string') {
    const n = serialFecha(v);
    if (n !== null) return `<c r="${ref}" s="${estilo}"><v>${n}</v></c>`;
  }
  if ((tipo === 'moneda' || tipo === 'numero') && typeof v === 'number' && isFinite(v)) {
    // Montos a 2 decimales y cantidades a 3: evita residuos de punto flotante (103131.99999999999)
    const n = tipo === 'moneda' ? Math.round(v * 100) / 100 : Math.round(v * 1000) / 1000;
    return `<c r="${ref}" s="${estilo}"><v>${n}</v></c>`;
  }
  return `<c r="${ref}" s="${estilo}" t="inlineStr"><is><t xml:space="preserve">${esc(String(v))}</t></is></c>`;
}

/** Arma un .xlsx (Office Open XML) con título, encabezados, filas y totales. */
export function libroExcel(h: HojaExcel): Uint8Array {
  const tipos = h.columnas.map((c) => c.tipo ?? 'texto');
  const filas: string[] = [];
  let r = 1;
  filas.push(`<row r="${r}"><c r="A${r}" s="7" t="inlineStr"><is><t>${esc(h.titulo)}</t></is></c></row>`);
  for (const s of h.subtitulos ?? []) {
    r++;
    filas.push(`<row r="${r}"><c r="A${r}" t="inlineStr"><is><t xml:space="preserve">${esc(s)}</t></is></c></row>`);
  }
  r += 2;
  const filaEncabezado = r;
  filas.push(`<row r="${r}">${h.columnas.map((c, i) => celda(`${columna(i)}${r}`, c.titulo, 'texto', 1)).join('')}</row>`);
  for (const f of h.filas) {
    r++;
    filas.push(`<row r="${r}">${f.map((v, i) => celda(`${columna(i)}${r}`, v, tipos[i] ?? 'texto', ESTILO[tipos[i] ?? 'texto'])).join('')}</row>`);
  }
  if (h.totales) {
    r++;
    filas.push(`<row r="${r}">${h.totales.map((v, i) => celda(`${columna(i)}${r}`, v, tipos[i] ?? 'texto', ESTILO_TOTAL[tipos[i] ?? 'texto'])).join('')}</row>`);
  }
  const anchos = h.columnas.map((c, i) => {
    const largo = Math.max(c.titulo.length, ...h.filas.slice(0, 200).map((f) => String(f[i] ?? '').length));
    const w = c.ancho ?? Math.min(60, Math.max(10, largo + 2, c.tipo === 'moneda' ? 14 : 0));
    return `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`;
  }).join('');
  const ultima = columna(h.columnas.length - 1);

  const hoja = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetViews><sheetView workbookViewId="0"><pane ySplit="${filaEncabezado}" topLeftCell="A${filaEncabezado + 1}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
<cols>${anchos}</cols><sheetData>${filas.join('')}</sheetData>
<autoFilter ref="A${filaEncabezado}:${ultima}${Math.max(filaEncabezado, r - (h.totales ? 1 : 0))}"/>
</worksheet>`;
  const estilos = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0.00"/><numFmt numFmtId="165" formatCode="dd/mm/yyyy"/></numFmts>
<fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="14"/><name val="Calibri"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="8">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="164" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`;
  const nombreHoja = esc(h.titulo.replace(/[\\/?*[\]:]/g, ' ').slice(0, 31));
  return zipSync({
    '[Content_Types].xml': bytes(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>`),
    '_rels/.rels': bytes(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>`),
    'xl/workbook.xml': bytes(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="${nombreHoja}" sheetId="1" r:id="rId1"/></sheets>
<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">'${nombreHoja.replace(/'/g, "''")}'!$A$${filaEncabezado}:$${ultima}$${Math.max(filaEncabezado, r - (h.totales ? 1 : 0))}</definedName></definedNames>
</workbook>`),
    'xl/_rels/workbook.xml.rels': bytes(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>`),
    'xl/worksheets/sheet1.xml': bytes(hoja),
    'xl/styles.xml': bytes(estilos),
  });
}

/** Genera y descarga el .xlsx. */
export function exportarExcel(h: HojaExcel): void {
  const datos = libroExcel(h);
  const blob = new Blob([datos.buffer.slice(datos.byteOffset, datos.byteOffset + datos.byteLength) as ArrayBuffer],
    { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
  descargar(blob, `${h.archivo}.xlsx`);
}

/** Encabezado común: "EMPRESA · RNC ..." y la fecha/hora de generación. */
export function subtitulosEmpresa(cia?: { nombre?: string; rnc?: string } | null, ...extra: string[]): string[] {
  const ahora = new Date();
  const dd = (n: number) => String(n).padStart(2, '0');
  const generado = `Generado el ${dd(ahora.getDate())}/${dd(ahora.getMonth() + 1)}/${ahora.getFullYear()} ${dd(ahora.getHours())}:${dd(ahora.getMinutes())}`;
  return [[cia?.nombre, cia?.rnc ? `RNC ${cia.rnc}` : ''].filter(Boolean).join(' · '), ...extra, generado].filter(Boolean);
}
