-- Origen del recibo: VENTA (cobro de una venta de contado en el punto de venta) o COBRO (recibo de ingreso de CxC).
-- El cierre de caja los separa: "Facturas a contado" y "Recibos de ingresos CxC".
ALTER TABLE recibo ADD COLUMN origen VARCHAR(5) NOT NULL DEFAULT 'COBRO' CHECK (origen IN ('VENTA', 'COBRO'));
-- Recibos existentes del punto de venta: el concepto es "Factura N" y pagan esa factura de contado del mismo día
UPDATE recibo r SET origen = 'VENTA'
 WHERE r.concepto LIKE 'Factura %'
   AND EXISTS (SELECT 1 FROM recibo_aplicacion a JOIN factura f ON f.id = a.documento_id
                WHERE a.recibo_id = r.id AND a.documento_tipo = 'FACTURA' AND f.condicion = 'CONTADO'
                  AND f.fecha = r.fecha AND r.concepto = 'Factura ' || f.numero);
