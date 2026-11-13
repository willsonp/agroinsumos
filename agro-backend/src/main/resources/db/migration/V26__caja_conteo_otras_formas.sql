-- Cuadre de caja como el sistema anterior: además del efectivo por denominación, el cajero declara lo que tiene en
-- vouchers de tarjeta, cheques y depósitos/transferencias. Null = no lo declaró (se toma lo del sistema).
ALTER TABLE caja_sesion ADD COLUMN tarjeta_contado NUMERIC(18,2);
ALTER TABLE caja_sesion ADD COLUMN cheque_contado NUMERIC(18,2);
ALTER TABLE caja_sesion ADD COLUMN transferencia_contado NUMERIC(18,2);
