-- =====================================================================
-- V7: Datos fiscales para los formatos 606/607 (Norma General 07-2018)
--     y enlace de compras/egresos con e-CF 41 (Compras) y 43 (Gastos Menores).
-- =====================================================================

-- Tipo de bienes y servicios comprados (606, campo 3):
-- 01 Gastos de personal, 02 Trabajos/suministros/servicios, 03 Arrendamientos, 04 Activos fijos,
-- 05 Representación, 06 Otras deducciones admitidas, 07 Financieros, 08 Extraordinarios,
-- 09 Compras que forman parte del costo de venta, 10 Adquisiciones de activos, 11 Seguros
ALTER TABLE compra
    ADD COLUMN tipo_bien_servicio VARCHAR(2) NOT NULL DEFAULT '09'
        CHECK (tipo_bien_servicio IN ('01','02','03','04','05','06','07','08','09','10','11')),
    -- Tipo de retención ISR (606, campo 17): 01 Alquileres, 02 Honorarios, 03 Otras rentas,
    -- 04 Otras rentas (presuntas), 05 Intereses a personas jurídicas, 06 Intereses a personas físicas,
    -- 07 Retención por proveedores del Estado, 08 Juegos telefónicos
    ADD COLUMN tipo_retencion_isr VARCHAR(2) CHECK (tipo_retencion_isr IN ('01','02','03','04','05','06','07','08')),
    -- e-CF 41 emitido por nosotros cuando el suplidor es informal (persona física sin comprobante)
    ADD COLUMN encf_compra VARCHAR(13),
    ADD COLUMN ecf_documento_id BIGINT REFERENCES ecf_documento(id),
    ADD CONSTRAINT ck_compra_ret_isr CHECK (isr_retenido = 0 OR tipo_retencion_isr IS NOT NULL);

ALTER TABLE caja_egreso
    ADD COLUMN tipo_bien_servicio VARCHAR(2) NOT NULL DEFAULT '02'
        CHECK (tipo_bien_servicio IN ('01','02','03','04','05','06','07','08','09','10','11')),
    -- e-CF 43 emitido por nosotros para gastos menores sin comprobante del proveedor
    ADD COLUMN ecf_documento_id BIGINT REFERENCES ecf_documento(id);

CREATE INDEX ix_compra_fecha ON compra (compania_id, fecha);
CREATE INDEX ix_egreso_fecha ON caja_egreso (compania_id, fecha);
