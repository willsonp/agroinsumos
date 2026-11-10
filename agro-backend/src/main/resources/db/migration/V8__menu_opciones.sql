-- =====================================================================
-- V8: Catálogo de módulos y opciones del menú web (frontend Angular).
-- Los códigos coinciden con los del frontend (src/app/core/navegacion.ts).
-- Los permisos por usuario se asignan en detalle_opciones_menu (los administradores ven todo).
-- Nota: el menú del Swing (nombres de componentes JFrame) no se migra; reasignar permisos.
-- =====================================================================
INSERT INTO main_menu (nombre_modulo, codigo, orden) VALUES
  ('Ventas', 'VENTAS', 10), ('Caja', 'CAJA', 20), ('Cuentas por Cobrar', 'CXC', 30),
  ('Inventario', 'INVENTARIO', 40), ('Compras', 'COMPRAS', 50), ('Catálogos', 'CATALOGOS', 60),
  ('Fiscal (DGII)', 'FISCAL', 70), ('Administración', 'ADMIN', 80);

INSERT INTO sub_menu (main_menu_id, nombre_opcion, codigo, ruta_frontend, orden)
SELECT m.id, o.nombre, o.codigo, o.ruta, o.orden
FROM (VALUES
  ('VENTAS', 'Punto de venta', 'VENTAS_POS', '/ventas/pos', 1),
  ('VENTAS', 'Facturas', 'VENTAS_FACTURAS', '/ventas/facturas', 2),
  ('CAJA', 'Mi caja', 'CAJA_SESION', '/caja', 1),
  ('CAJA', 'Supervisión de cajas', 'CAJA_SESIONES', '/caja/sesiones', 2),
  ('CXC', 'Cobros', 'CXC_RECIBOS', '/cxc/cobros', 1),
  ('CXC', 'Estado de cuenta', 'CXC_ESTADO_CUENTA', '/cxc/estado-cuenta', 2),
  ('INVENTARIO', 'Artículos', 'INV_ARTICULOS', '/inventario/articulos', 1),
  ('COMPRAS', 'Compras', 'COMPRAS_REGISTRO', '/compras', 1),
  ('CATALOGOS', 'Clientes', 'CAT_CLIENTES', '/catalogos/clientes', 1),
  ('CATALOGOS', 'Proveedores', 'CAT_PROVEEDORES', '/catalogos/proveedores', 2),
  ('CATALOGOS', 'Catálogos generales', 'CAT_GENERALES', '/catalogos/generales', 3),
  ('FISCAL', 'Comprobantes electrónicos', 'ECF_DOCUMENTOS', '/fiscal/ecf', 1),
  ('FISCAL', 'Secuencias e-NCF', 'ECF_SECUENCIAS', '/fiscal/secuencias', 2),
  ('FISCAL', 'Formatos 606 / 607', 'FISCAL_FORMATOS', '/fiscal/formatos', 3),
  ('ADMIN', 'Usuarios y permisos', 'ADMIN_USUARIOS', '/admin/usuarios', 1)
) AS o(modulo, nombre, codigo, ruta, orden)
JOIN main_menu m ON m.codigo = o.modulo;
