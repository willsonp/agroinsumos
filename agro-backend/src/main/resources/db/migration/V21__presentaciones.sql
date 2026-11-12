-- =====================================================================
-- V21: Presentaciones (empaques) de un artículo: se compra y vende por Saco/Caja además de por la unidad base.
-- El inventario se lleva siempre en la unidad base (Libra, Unidad); la presentación equivale a "factor" unidades.
-- Ej.: Urea en libras con presentación Saco de 100 lb; Herbicida en unidades con presentación Caja de 12.
-- =====================================================================
ALTER TABLE articulo
    ADD COLUMN unidad_empaque_id       BIGINT REFERENCES unidad(id),
    ADD COLUMN factor_empaque          NUMERIC(14,3),
    ADD COLUMN precio_empaque_contado  NUMERIC(18,4),   -- sin ITBIS, como los demás precios; NULL = precio base × factor
    ADD COLUMN precio_empaque_credito  NUMERIC(18,4),
    ADD COLUMN precio_empaque_mayor    NUMERIC(18,4),
    ADD COLUMN codigo_barra_empaque    VARCHAR(20),
    ADD CONSTRAINT ck_articulo_empaque CHECK (
        (unidad_empaque_id IS NULL AND factor_empaque IS NULL) OR (unidad_empaque_id IS NOT NULL AND factor_empaque > 1));
CREATE UNIQUE INDEX uk_articulo_codigo_empaque ON articulo (compania_id, codigo_barra_empaque) WHERE codigo_barra_empaque IS NOT NULL;

-- Cada línea guarda la cantidad tal como se vendió/compró (p.ej. 2 sacos) y cuántas unidades base equivale cada una.
-- Movimiento de inventario = cantidad × factor.
ALTER TABLE factura_linea         ADD COLUMN factor NUMERIC(14,3) NOT NULL DEFAULT 1 CHECK (factor > 0);
ALTER TABLE cotizacion_linea      ADD COLUMN factor NUMERIC(14,3) NOT NULL DEFAULT 1 CHECK (factor > 0);
ALTER TABLE conduce_linea         ADD COLUMN factor NUMERIC(14,3) NOT NULL DEFAULT 1 CHECK (factor > 0);
ALTER TABLE apartado_linea        ADD COLUMN factor NUMERIC(14,3) NOT NULL DEFAULT 1 CHECK (factor > 0);
ALTER TABLE compra_linea          ADD COLUMN factor NUMERIC(14,3) NOT NULL DEFAULT 1 CHECK (factor > 0);
ALTER TABLE nota_proveedor_linea  ADD COLUMN factor NUMERIC(14,3) NOT NULL DEFAULT 1 CHECK (factor > 0);
