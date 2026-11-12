/**
 * Menú de la aplicación. Los códigos coinciden con sub_menu.codigo (migración V8 del backend):
 * un usuario ve las opciones que tiene asignadas en "Usuarios y permisos"; el administrador ve todo.
 */
/** Nivel mínimo que además exige el backend para la opción (además de tenerla asignada). */
export type RolMinimo = 'SUPERVISOR' | 'ADMIN';
export interface Opcion { codigo: string; nombre: string; ruta: string; icono: string; rol?: RolMinimo; }
export interface Modulo { nombre: string; icono: string; opciones: Opcion[]; }

export const NAVEGACION: Modulo[] = [
  { nombre: 'Ventas', icono: 'point_of_sale', opciones: [
    { codigo: 'VENTAS_POS', nombre: 'Punto de venta', ruta: '/ventas/pos', icono: 'shopping_cart' },
    { codigo: 'VENTAS_FACTURAS', nombre: 'Facturas', ruta: '/ventas/facturas', icono: 'receipt_long' },
    { codigo: 'VENTAS_COTIZACIONES', nombre: 'Cotizaciones', ruta: '/ventas/cotizaciones', icono: 'request_page' },
    { codigo: 'VENTAS_CONDUCES', nombre: 'Conduces', ruta: '/ventas/conduces', icono: 'local_shipping' },
    { codigo: 'VENTAS_APARTADOS', nombre: 'Apartados', ruta: '/ventas/apartados', icono: 'bookmark_added' },
  ]},
  { nombre: 'Caja', icono: 'payments', opciones: [
    { codigo: 'CAJA_SESION', nombre: 'Mi caja', ruta: '/caja', icono: 'account_balance_wallet' },
    { codigo: 'CAJA_SESIONES', nombre: 'Supervisión de cajas', ruta: '/caja/sesiones', icono: 'fact_check', rol: 'SUPERVISOR' },
    { codigo: 'CAJA_CHICA', nombre: 'Caja chica', ruta: '/caja/chica', icono: 'wallet' },
  ]},
  { nombre: 'Cuentas por Cobrar', icono: 'request_quote', opciones: [
    { codigo: 'CXC_RECIBOS', nombre: 'Cobros', ruta: '/cxc/cobros', icono: 'price_check' },
    { codigo: 'CXC_ESTADO_CUENTA', nombre: 'Estado de cuenta', ruta: '/cxc/estado-cuenta', icono: 'account_balance' },
    { codigo: 'CXC_FINANCIAMIENTO', nombre: 'Ventas financiadas', ruta: '/cxc/financiamientos', icono: 'event_repeat' },
  ]},
  { nombre: 'Inventario', icono: 'inventory_2', opciones: [
    { codigo: 'INV_ARTICULOS', nombre: 'Artículos', ruta: '/inventario/articulos', icono: 'category' },
    { codigo: 'INV_DOCUMENTOS', nombre: 'Ajustes y transferencias', ruta: '/inventario/documentos', icono: 'swap_horiz', rol: 'SUPERVISOR' },
    { codigo: 'INV_ETIQUETAS', nombre: 'Imprimir etiquetas', ruta: '/inventario/etiquetas', icono: 'barcode' },
  ]},
  { nombre: 'Compras', icono: 'local_shipping', opciones: [
    { codigo: 'COMPRAS_REGISTRO', nombre: 'Compras', ruta: '/compras', icono: 'add_shopping_cart', rol: 'SUPERVISOR' },
    { codigo: 'COMPRAS_NOTAS', nombre: 'Notas de suplidores', ruta: '/compras/notas', icono: 'note_alt', rol: 'SUPERVISOR' },
  ]},
  { nombre: 'Banco', icono: 'account_balance', opciones: [
    { codigo: 'BANCO_MOVIMIENTOS', nombre: 'Movimientos bancarios', ruta: '/banco/movimientos', icono: 'swap_vert', rol: 'SUPERVISOR' },
    { codigo: 'BANCO_CONCILIACION', nombre: 'Conciliación', ruta: '/banco/conciliacion', icono: 'rule', rol: 'SUPERVISOR' },
    { codigo: 'BANCO_CUENTAS', nombre: 'Cuentas bancarias', ruta: '/banco/cuentas', icono: 'savings' },
    { codigo: 'BANCO_TASAS', nombre: 'Tasas de cambio', ruta: '/banco/tasas', icono: 'currency_exchange', rol: 'SUPERVISOR' },
  ]},
  { nombre: 'Contabilidad', icono: 'calculate', opciones: [
    { codigo: 'CONT_ASIENTOS', nombre: 'Asientos contables', ruta: '/contabilidad/asientos', icono: 'menu_book', rol: 'SUPERVISOR' },
    { codigo: 'CONT_REPORTES', nombre: 'Estados financieros', ruta: '/contabilidad/reportes', icono: 'monitoring', rol: 'SUPERVISOR' },
    { codigo: 'CONT_CATALOGO', nombre: 'Catálogo de cuentas', ruta: '/contabilidad/catalogo', icono: 'account_tree', rol: 'SUPERVISOR' },
    { codigo: 'CONT_PERIODOS', nombre: 'Cierre de períodos', ruta: '/contabilidad/periodos', icono: 'lock_clock', rol: 'ADMIN' },
  ]},
  { nombre: 'Reportes', icono: 'summarize', opciones: [
    { codigo: 'REP_VENTAS', nombre: 'Ventas y utilidad', ruta: '/reportes/ventas', icono: 'trending_up', rol: 'SUPERVISOR' },
    { codigo: 'REP_CXC_CAJA', nombre: 'Cobros y caja', ruta: '/reportes/cobros', icono: 'payments', rol: 'SUPERVISOR' },
    { codigo: 'REP_INVENTARIO', nombre: 'Inventario', ruta: '/reportes/inventario', icono: 'inventory', rol: 'SUPERVISOR' },
    { codigo: 'REP_COMPRAS', nombre: 'Compras', ruta: '/reportes/compras', icono: 'shopping_bag', rol: 'SUPERVISOR' },
  ]},
  { nombre: 'Catálogos', icono: 'menu_book', opciones: [
    { codigo: 'CAT_CLIENTES', nombre: 'Clientes', ruta: '/catalogos/clientes', icono: 'groups' },
    { codigo: 'CAT_PROVEEDORES', nombre: 'Proveedores', ruta: '/catalogos/proveedores', icono: 'storefront' },
    { codigo: 'CAT_GENERALES', nombre: 'Catálogos generales', ruta: '/catalogos/generales', icono: 'tune' },
  ]},
  { nombre: 'Fiscal (DGII)', icono: 'gavel', opciones: [
    { codigo: 'ECF_DOCUMENTOS', nombre: 'Comprobantes electrónicos', ruta: '/fiscal/ecf', icono: 'verified' },
    { codigo: 'ECF_SECUENCIAS', nombre: 'Secuencias de comprobantes', ruta: '/fiscal/secuencias', icono: 'pin', rol: 'SUPERVISOR' },
    { codigo: 'FISCAL_FORMATOS', nombre: 'Formatos 606 / 607 / 608', ruta: '/fiscal/formatos', icono: 'description', rol: 'SUPERVISOR' },
  ]},
  { nombre: 'Administración', icono: 'admin_panel_settings', opciones: [
    { codigo: 'ADMIN_USUARIOS', nombre: 'Usuarios y permisos', ruta: '/admin/usuarios', icono: 'manage_accounts', rol: 'ADMIN' },
    { codigo: 'ADMIN_RESPALDOS', nombre: 'Respaldos', ruta: '/admin/respaldos', icono: 'backup', rol: 'ADMIN' },
    { codigo: 'ADMIN_EMPRESA', nombre: 'Datos de la empresa', ruta: '/admin/empresa', icono: 'domain', rol: 'ADMIN' },
  ]},
];

/** Opción por código (para guards y para mostrar el rol requerido). */
export const OPCIONES: Record<string, Opcion> = Object.fromEntries(
  NAVEGACION.flatMap((m) => m.opciones).map((o) => [o.codigo, o]));
