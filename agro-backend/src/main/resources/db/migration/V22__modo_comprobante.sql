-- =====================================================================
-- V22: Modo de comprobante fiscal de la empresa
--   ECF     facturación electrónica (e-CF firmado y enviado a la DGII) — lo que había hasta ahora
--   NCF     comprobantes tradicionales serie B (B01 crédito fiscal, B02 consumo, B04 nota de crédito...) de
--           rangos autorizados por la DGII; van al 607 igual que antes
--   NINGUNO sin comprobante fiscal: la factura solo lleva su número interno (empresas no acogidas)
-- =====================================================================
ALTER TABLE compania ADD COLUMN modo_comprobante VARCHAR(7) NOT NULL DEFAULT 'ECF'
    CHECK (modo_comprobante IN ('ECF', 'NCF', 'NINGUNO'));

-- Los rangos de NCF serie B usan la misma tabla de secuencias (mismo código de tipo: 31 ↔ B01, 32 ↔ B02, 34 ↔ B04...)
ALTER TABLE ecf_secuencia ADD COLUMN serie CHAR(1) NOT NULL DEFAULT 'E' CHECK (serie IN ('E', 'B'));
DROP INDEX uk_ecf_secuencia_activa;
CREATE UNIQUE INDEX uk_ecf_secuencia_activa ON ecf_secuencia (compania_id, serie, tipo_ecf) WHERE activa;
