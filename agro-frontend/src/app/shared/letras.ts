const UNIDADES = ['', 'UN', 'DOS', 'TRES', 'CUATRO', 'CINCO', 'SEIS', 'SIETE', 'OCHO', 'NUEVE', 'DIEZ', 'ONCE', 'DOCE', 'TRECE',
  'CATORCE', 'QUINCE', 'DIECISÉIS', 'DIECISIETE', 'DIECIOCHO', 'DIECINUEVE', 'VEINTE', 'VEINTIÚN', 'VEINTIDÓS', 'VEINTITRÉS',
  'VEINTICUATRO', 'VEINTICINCO', 'VEINTISÉIS', 'VEINTISIETE', 'VEINTIOCHO', 'VEINTINUEVE'];
const DECENAS = ['', '', '', 'TREINTA', 'CUARENTA', 'CINCUENTA', 'SESENTA', 'SETENTA', 'OCHENTA', 'NOVENTA'];
const CENTENAS = ['', 'CIENTO', 'DOSCIENTOS', 'TRESCIENTOS', 'CUATROCIENTOS', 'QUINIENTOS', 'SEISCIENTOS', 'SETECIENTOS',
  'OCHOCIENTOS', 'NOVECIENTOS'];

/** 0..999 en letras ("UN" en vez de "UNO": se usa delante de "MIL", "MILLONES" o "PESOS"). */
function centenas(n: number): string {
  if (n === 100) return 'CIEN';
  const c = Math.floor(n / 100), r = n % 100;
  const resto = r < 30 ? UNIDADES[r] : DECENAS[Math.floor(r / 10)] + (r % 10 ? ' Y ' + UNIDADES[r % 10] : '');
  return [CENTENAS[c], resto].filter(Boolean).join(' ');
}

function entero(n: number): string {
  if (n === 0) return 'CERO';
  const millones = Math.floor(n / 1_000_000), miles = Math.floor((n % 1_000_000) / 1000), resto = n % 1000;
  const partes: string[] = [];
  if (millones) partes.push(millones === 1 ? 'UN MILLÓN' : entero(millones) + ' MILLONES');
  if (miles) partes.push(miles === 1 ? 'MIL' : centenas(miles) + ' MIL');
  if (resto) partes.push(centenas(resto));
  return partes.join(' ');
}

/**
 * Monto en letras para recibos: 1000 → "MIL PESOS", 1525.5 → "MIL QUINIENTOS VEINTICINCO PESOS CON 50/100",
 * 1000000 → "UN MILLÓN DE PESOS".
 */
export function montoEnLetras(monto: number): string {
  const centavos = Math.round(Math.abs(monto) * 100);
  const pesos = Math.floor(centavos / 100), cts = centavos % 100;
  const texto = entero(pesos);
  const de = pesos > 0 && pesos % 1_000_000 === 0 ? ' DE' : '';
  return `${texto}${de} ${pesos === 1 ? 'PESO' : 'PESOS'}${cts ? ` CON ${String(cts).padStart(2, '0')}/100` : ''}`;
}
