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
  ]},
  { nombre: 'Caja', icono: 'payments', opciones: [
    { codigo: 'CAJA_SESION', nombre: 'Mi caja', ruta: '/caja', icono: 'account_balance_wallet' },
    { codigo: 'CAJA_SESIONES', nombre: 'Supervisión de cajas', ruta: '/caja/sesiones', icono: 'fact_check', rol: 'SUPERVISOR' },
  ]},
  { nombre: 'Cuentas por Cobrar', icono: 'request_quote', opciones: [
    { codigo: 'CXC_RECIBOS', nombre: 'Cobros', ruta: '/cxc/cobros', icono: 'price_check' },
    { codigo: 'CXC_ESTADO_CUENTA', nombre: 'Estado de cuenta', ruta: '/cxc/estado-cuenta', icono: 'account_balance' },
  ]},
  { nombre: 'Inventario', icono: 'inventory_2', opciones: [
    { codigo: 'INV_ARTICULOS', nombre: 'Artículos', ruta: '/inventario/articulos', icono: 'category' },
    { codigo: 'INV_DOCUMENTOS', nombre: 'Ajustes y transferencias', ruta: '/inventario/documentos', icono: 'swap_horiz', rol: 'SUPERVISOR' },
  ]},
  { nombre: 'Compras', icono: 'local_shipping', opciones: [
    { codigo: 'COMPRAS_REGISTRO', nombre: 'Compras', ruta: '/compras', icono: 'add_shopping_cart', rol: 'SUPERVISOR' },
  ]},
  { nombre: 'Catálogos', icono: 'menu_book', opciones: [
    { codigo: 'CAT_CLIENTES', nombre: 'Clientes', ruta: '/catalogos/clientes', icono: 'groups' },
    { codigo: 'CAT_PROVEEDORES', nombre: 'Proveedores', ruta: '/catalogos/proveedores', icono: 'storefront' },
    { codigo: 'CAT_GENERALES', nombre: 'Catálogos generales', ruta: '/catalogos/generales', icono: 'tune' },
  ]},
  { nombre: 'Fiscal (DGII)', icono: 'gavel', opciones: [
    { codigo: 'ECF_DOCUMENTOS', nombre: 'Comprobantes electrónicos', ruta: '/fiscal/ecf', icono: 'verified' },
    { codigo: 'ECF_SECUENCIAS', nombre: 'Secuencias e-NCF', ruta: '/fiscal/secuencias', icono: 'pin', rol: 'SUPERVISOR' },
    { codigo: 'FISCAL_FORMATOS', nombre: 'Formatos 606 / 607', ruta: '/fiscal/formatos', icono: 'description', rol: 'SUPERVISOR' },
  ]},
  { nombre: 'Administración', icono: 'admin_panel_settings', opciones: [
    { codigo: 'ADMIN_USUARIOS', nombre: 'Usuarios y permisos', ruta: '/admin/usuarios', icono: 'manage_accounts', rol: 'ADMIN' },
  ]},
];

/** Opción por código (para guards y para mostrar el rol requerido). */
export const OPCIONES: Record<string, Opcion> = Object.fromEntries(
  NAVEGACION.flatMap((m) => m.opciones).map((o) => [o.codigo, o]));
