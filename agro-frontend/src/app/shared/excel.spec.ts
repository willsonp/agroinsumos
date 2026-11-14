import { describe, expect, it } from 'vitest';
import { strFromU8, unzipSync } from 'fflate';
import { libroExcel } from './excel';

describe('libroExcel', () => {
  it('arma un .xlsx con título, encabezados, montos numéricos, fechas y totales', () => {
    const datos = libroExcel({
      archivo: 'prueba', titulo: 'Antigüedad de saldos', subtitulos: ['AGRO & CIA <SRL>'],
      columnas: [{ titulo: 'Suplidor' }, { titulo: 'Fecha', tipo: 'fecha' }, { titulo: 'Total', tipo: 'moneda' }],
      filas: [['AGROCASTE', '2026-11-13', 1670.5], ['Otro "S.A."', null, 0]],
      totales: ['TOTAL', null, 1670.5],
    });
    const zip = unzipSync(datos);
    expect(Object.keys(zip)).toContain('xl/worksheets/sheet1.xml');
    const hoja = strFromU8(zip['xl/worksheets/sheet1.xml']);
    expect(hoja).toContain('AGRO &amp; CIA &lt;SRL&gt;');            // texto escapado
    expect(hoja).toContain('<c r="C5" s="2"><v>1670.5</v></c>');      // monto como número con formato
    expect(hoja).toContain('<c r="B5" s="4"><v>46339</v></c>');       // 13/11/2026 como fecha de Excel
    expect(hoja).toContain('<c r="C7" s="5"><v>1670.5</v></c>');      // total en negrita
    expect(hoja).toContain('Otro &quot;S.A.&quot;');
  });
});
