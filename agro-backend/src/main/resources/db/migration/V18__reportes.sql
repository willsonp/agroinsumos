-- =====================================================================
-- V18: Módulo de reportes (opciones por grupo; los reportes son de supervisor o administrador)
-- =====================================================================
INSERT INTO main_menu (nombre_modulo, codigo, orden) VALUES ('Reportes', 'REPORTES', 75);
INSERT INTO sub_menu (main_menu_id, nombre_opcion, codigo, ruta_frontend, orden)
SELECT m.id, o.nombre, o.codigo, o.ruta, o.orden
FROM (VALUES
  ('Reportes de ventas', 'REP_VENTAS', '/reportes/ventas', 1),
  ('Reportes de cobros y caja', 'REP_CXC_CAJA', '/reportes/cobros', 2),
  ('Reportes de inventario', 'REP_INVENTARIO', '/reportes/inventario', 3),
  ('Reportes de compras', 'REP_COMPRAS', '/reportes/compras', 4)
) AS o(nombre, codigo, ruta, orden)
CROSS JOIN main_menu m WHERE m.codigo = 'REPORTES';
