/** Color de identidad de cada módulo (menú lateral e inicio): tono del ícono y fondo suave. */
export interface ColorModulo { tono: string; fondo: string; }

const COLORES: Record<string, ColorModulo> = {
  'Ventas': { tono: '#1b7a3a', fondo: '#dff3e4' },
  'Caja': { tono: '#9a5b00', fondo: '#fdecd2' },
  'Cuentas por Cobrar': { tono: '#0b5cad', fondo: '#dceafb' },
  'Inventario': { tono: '#6b3fa0', fondo: '#ece2f8' },
  'Compras': { tono: '#b2420e', fondo: '#fbe3d8' },
  'Banco': { tono: '#00707a', fondo: '#d6f1f2' },
  'Contabilidad': { tono: '#4a5a14', fondo: '#eaf0d3' },
  'Reportes': { tono: '#a3195b', fondo: '#f9dde9' },
  'Catálogos': { tono: '#3c4fb0', fondo: '#e1e5fb' },
  'Fiscal (DGII)': { tono: '#7a4b24', fondo: '#f1e5d9' },
  'Administración': { tono: '#44515c', fondo: '#e3e8ec' },
};

export function colorModulo(nombre: string): ColorModulo {
  return COLORES[nombre] ?? { tono: '#1b7a3a', fondo: '#dff3e4' };
}
