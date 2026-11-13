-- Cuentas por pagar como el sistema anterior: pantalla de pagos a suplidores (pagar o abonar varias compras,
-- distribuir un monto, antigüedad de saldos) y comprobante de pago impreso con el balance que quedó.
INSERT INTO sub_menu (main_menu_id, nombre_opcion, codigo, ruta_frontend, orden)
SELECT s.main_menu_id, 'Pagos a suplidores', 'CXP_PAGOS', '/compras/pagos', 2
FROM sub_menu s WHERE s.codigo = 'COMPRAS_REGISTRO';

-- Quien ya registra compras (y pagaba desde allí) recibe la nueva opción
INSERT INTO detalle_opciones_menu (sub_menu_id, usuario_id, permitido)
SELECT n.id, d.usuario_id, d.permitido
  FROM detalle_opciones_menu d
  JOIN sub_menu s ON s.id = d.sub_menu_id AND s.codigo = 'COMPRAS_REGISTRO'
  CROSS JOIN (SELECT id FROM sub_menu WHERE codigo = 'CXP_PAGOS') n
ON CONFLICT DO NOTHING;

-- Fotografía del pago para el comprobante (las reimpresiones muestran lo de ese momento)
ALTER TABLE pago_proveedor_aplicacion ADD COLUMN balance_despues NUMERIC(18,2);      -- en la moneda del pago
ALTER TABLE pago_proveedor ADD COLUMN balance_proveedor_despues NUMERIC(18,2);       -- en pesos
