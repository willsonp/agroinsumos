import type { CatalogoConfig } from '../../shared/catalogo-crud';

export const BANCOS: CatalogoConfig = {
  titulo: 'Banco', url: '/api/v1/banco/bancos', permisoEscritura: 'BANCO_CUENTAS', orden: 'nombre',
  columnas: [{ campo: 'nombre', etiqueta: 'Banco' }, { campo: 'estado', etiqueta: 'Estado', tipo: 'estado' }],
  nuevo: {},
  campos: [{ nombre: 'nombre', etiqueta: 'Nombre del banco', requerido: true, anchoCompleto: true }],
};

export const CUENTAS_BANCARIAS: CatalogoConfig = {
  titulo: 'Cuenta bancaria', url: '/api/v1/banco/cuentas', permisoEscritura: 'BANCO_CUENTAS', orden: 'nombre',
  columnas: [{ campo: 'banco', etiqueta: 'Banco' }, { campo: 'nombre', etiqueta: 'Cuenta' }, { campo: 'numero', etiqueta: 'Número' },
    { campo: 'tipo', etiqueta: 'Tipo' }, { campo: 'proximoCheque', etiqueta: 'Próx. cheque', tipo: 'numero' },
    { campo: 'saldo', etiqueta: 'Saldo en libros', tipo: 'moneda' }, { campo: 'estado', etiqueta: 'Estado', tipo: 'estado' }],
  nuevo: { tipo: 'CORRIENTE', saldoInicial: 0, proximoCheque: 1 },
  campos: [
    { nombre: 'bancoId', etiqueta: 'Banco', tipo: 'select', requerido: true, opcionesUrl: '/api/v1/banco/bancos' },
    { nombre: 'numero', etiqueta: 'Número de cuenta', requerido: true },
    { nombre: 'nombre', etiqueta: 'Nombre descriptivo', requerido: true },
    { nombre: 'tipo', etiqueta: 'Tipo', tipo: 'select', requerido: true, opciones: [
      { valor: 'CORRIENTE', texto: 'Corriente' }, { valor: 'AHORRO', texto: 'Ahorro' }] },
    { nombre: 'saldoInicial', etiqueta: 'Saldo inicial', tipo: 'numero', requerido: true },
    { nombre: 'fechaSaldoInicial', etiqueta: 'Fecha del saldo inicial', tipo: 'fecha',
      ayuda: 'No se aceptan movimientos anteriores a esta fecha' },
    { nombre: 'proximoCheque', etiqueta: 'Próximo No. de cheque', tipo: 'numero', min: 1 },
    { nombre: 'cuentaContable', etiqueta: 'Cuenta contable' },
  ],
};
