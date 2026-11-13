-- Recibo de ingreso impreso: balance que quedó en cada documento y balance total del cliente después del cobro
-- (fotografía del momento; las reimpresiones muestran lo mismo aunque luego haya otros cobros).
ALTER TABLE recibo_aplicacion ADD COLUMN balance_despues NUMERIC(18,2);
ALTER TABLE recibo ADD COLUMN balance_cliente_despues NUMERIC(18,2);
