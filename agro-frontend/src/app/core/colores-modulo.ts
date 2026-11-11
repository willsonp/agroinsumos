/** Color de identidad de cada módulo (menú lateral e inicio): tono del ícono y fondo suave, en modo claro y oscuro. */
export interface ColorModulo { tono: string; fondo: string; }

const COLORES: Record<string, ColorModulo> = {
  'Ventas': { tono: 'light-dark(#1b7a3a, #8fdca5)', fondo: 'light-dark(#dff3e4, #173a22)' },
  'Caja': { tono: 'light-dark(#9a5b00, #ffc777)', fondo: 'light-dark(#fdecd2, #3d2a08)' },
  'Cuentas por Cobrar': { tono: 'light-dark(#0b5cad, #9fc8ff)', fondo: 'light-dark(#dceafb, #14304f)' },
  'Inventario': { tono: 'light-dark(#6b3fa0, #d2b5ff)', fondo: 'light-dark(#ece2f8, #2e1f45)' },
  'Compras': { tono: 'light-dark(#b2420e, #ffb38f)', fondo: 'light-dark(#fbe3d8, #47200c)' },
  'Banco': { tono: 'light-dark(#00707a, #7fdbe3)', fondo: 'light-dark(#d6f1f2, #0b3438)' },
  'Contabilidad': { tono: 'light-dark(#4a5a14, #c5d98a)', fondo: 'light-dark(#eaf0d3, #28300f)' },
  'Reportes': { tono: 'light-dark(#a3195b, #ffa8cc)', fondo: 'light-dark(#f9dde9, #45152c)' },
  'Catálogos': { tono: 'light-dark(#3c4fb0, #b3bfff)', fondo: 'light-dark(#e1e5fb, #1f2650)' },
  'Fiscal (DGII)': { tono: 'light-dark(#7a4b24, #e8bf95)', fondo: 'light-dark(#f1e5d9, #36261a)' },
  'Administración': { tono: 'light-dark(#44515c, #bfcbd5)', fondo: 'light-dark(#e3e8ec, #273038)' },
};

export function colorModulo(nombre: string): ColorModulo {
  return COLORES[nombre] ?? COLORES['Ventas'];
}
