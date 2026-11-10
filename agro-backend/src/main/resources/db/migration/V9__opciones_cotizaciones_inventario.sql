-- =====================================================================
-- V9: Opciones de menú para Cotizaciones y Documentos de inventario
-- (ajustes por conteo, entradas, salidas y transferencias).
-- =====================================================================
INSERT INTO sub_menu (main_menu_id, nombre_opcion, codigo, ruta_frontend, orden)
SELECT m.id, o.nombre, o.codigo, o.ruta, o.orden
FROM (VALUES
  ('VENTAS', 'Cotizaciones', 'VENTAS_COTIZACIONES', '/ventas/cotizaciones', 3),
  ('INVENTARIO', 'Ajustes y transferencias', 'INV_DOCUMENTOS', '/inventario/documentos', 2)
) AS o(modulo, nombre, codigo, ruta, orden)
JOIN main_menu m ON m.codigo = o.modulo;
