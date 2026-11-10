-- =====================================================================
-- V17: Teléfono de la empresa (legacy no lo tenía en compania) y opción para editar los datos de la empresa
-- =====================================================================
ALTER TABLE compania ADD COLUMN telefono VARCHAR(30);

INSERT INTO sub_menu (main_menu_id, nombre_opcion, codigo, ruta_frontend, orden)
SELECT m.id, 'Datos de la empresa', 'ADMIN_EMPRESA', '/admin/empresa', 3 FROM main_menu m WHERE m.codigo = 'ADMIN';
