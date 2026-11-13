import type { CatalogoConfig } from '../../shared/catalogo-crud';

const RNC_AYUDA = 'RNC (9) o cédula (11), sin guiones';

export const CLIENTES: CatalogoConfig = {
  titulo: 'Cliente', url: '/api/v1/catalogos/clientes', permisoEscritura: 'CAT_CLIENTES', orden: 'nombre',
  columnas: [{ campo: 'nombre', etiqueta: 'Nombre' }, { campo: 'rncCedula', etiqueta: 'RNC/Cédula' },
    { campo: 'tipoEcfPreferido', etiqueta: 'e-CF' }, { campo: 'tipoPrecio', etiqueta: 'Lista' },
    { campo: 'limiteCredito', etiqueta: 'Límite crédito', tipo: 'moneda' }, { campo: 'saldoAFavor', etiqueta: 'A favor', tipo: 'moneda' },
    { campo: 'estado', etiqueta: 'Estado', tipo: 'estado' }],
  nuevo: { tipoEcfPreferido: 32, tipoPrecio: 'CONTADO', limiteCredito: 0, diasCredito: 0, exentoItbis: false },
  campos: [
    { nombre: 'nombre', etiqueta: 'Nombre o razón social', requerido: true, anchoCompleto: true },
    { nombre: 'rncCedula', etiqueta: 'RNC / Cédula', ayuda: RNC_AYUDA },
    { nombre: 'tipoEcfPreferido', etiqueta: 'Comprobante', tipo: 'select', requerido: true, opciones: [
      { valor: 32, texto: '32 · Consumo' }, { valor: 31, texto: '31 · Crédito fiscal' },
      { valor: 44, texto: '44 · Régimen especial' }, { valor: 45, texto: '45 · Gubernamental' }] },
    { nombre: 'tipoPrecio', etiqueta: 'Lista de precios', tipo: 'select', requerido: true, opciones: [
      { valor: 'CONTADO', texto: 'Contado' }, { valor: 'CREDITO', texto: 'Crédito' }, { valor: 'MAYOR', texto: 'Por mayor' }] },
    { nombre: 'limiteCredito', etiqueta: 'Límite de crédito (0 = sin límite)', tipo: 'numero', requerido: true, min: 0 },
    { nombre: 'diasCredito', etiqueta: 'Días de crédito', tipo: 'numero', min: 0, max: 365 },
    { nombre: 'exentoItbis', etiqueta: 'Exento de ITBIS', tipo: 'check' },
    { nombre: 'telefono', etiqueta: 'Teléfono' }, { nombre: 'celular', etiqueta: 'Celular' },
    { nombre: 'correo', etiqueta: 'Correo', tipo: 'email' },
    { nombre: 'ciudadId', etiqueta: 'Ciudad', tipo: 'select', opcionesUrl: '/api/v1/catalogos/ciudades' },
    { nombre: 'zonaId', etiqueta: 'Zona', tipo: 'select', opcionesUrl: '/api/v1/catalogos/zonas' },
    { nombre: 'direccion', etiqueta: 'Dirección', anchoCompleto: true },
    { nombre: 'contacto', etiqueta: 'Contacto' }, { nombre: 'cuentaContable', etiqueta: 'Cuenta contable' },
  ],
};

export const PROVEEDORES: CatalogoConfig = {
  titulo: 'Proveedor', url: '/api/v1/catalogos/proveedores', permisoEscritura: 'CAT_PROVEEDORES', orden: 'nombre',
  columnas: [{ campo: 'nombre', etiqueta: 'Nombre' }, { campo: 'rncCedula', etiqueta: 'RNC/Cédula' },
    { campo: 'telefono', etiqueta: 'Teléfono' }, { campo: 'contacto', etiqueta: 'Contacto' }, { campo: 'estado', etiqueta: 'Estado', tipo: 'estado' }],
  nuevo: {},
  campos: [
    { nombre: 'nombre', etiqueta: 'Nombre o razón social', requerido: true, anchoCompleto: true },
    { nombre: 'rncCedula', etiqueta: 'RNC / Cédula', ayuda: 'Obligatorio para el 606 y el e-CF 41' },
    { nombre: 'telefono', etiqueta: 'Teléfono' }, { nombre: 'correo', etiqueta: 'Correo', tipo: 'email' },
    { nombre: 'contacto', etiqueta: 'Contacto' }, { nombre: 'sector', etiqueta: 'Sector' },
    { nombre: 'ciudadId', etiqueta: 'Ciudad', tipo: 'select', opcionesUrl: '/api/v1/catalogos/ciudades' },
    { nombre: 'direccion', etiqueta: 'Dirección', anchoCompleto: true }, { nombre: 'cuentaContable', etiqueta: 'Cuenta contable' },
  ],
};

export const ARTICULOS: CatalogoConfig = {
  titulo: 'Artículo', url: '/api/v1/inventario/articulos', permisoEscritura: 'INV_ARTICULOS', orden: 'nombre',
  columnas: [{ campo: 'codigoBarra', etiqueta: 'Código' }, { campo: 'nombre', etiqueta: 'Nombre' }, { campo: 'tipo', etiqueta: 'Tipo' },
    { campo: 'precioContadoConItbis', etiqueta: 'Precio (con ITBIS)', tipo: 'moneda' }, { campo: 'costoPromedio', etiqueta: 'Costo prom.', tipo: 'moneda' },
    { campo: 'existencia', etiqueta: 'Existencia', tipo: 'numero' }, { campo: 'estado', etiqueta: 'Estado', tipo: 'estado' }],
  nuevo: { tipo: 'B', indicadorItbis: 1, costo: 0, precioContado: 0, preciosIncluyenItbis: true, descuentoMaximo: 0, stockMinimo: 0, manejaVencimiento: false },
  // El backend guarda precios SIN ITBIS: al editar se muestran con ITBIS para que la casilla sea coherente
  alEditar: (r) => ({ ...r, precioContado: r['precioContadoConItbis'], precioCredito: r['precioCreditoConItbis'],
    precioMayor: r['precioMayorConItbis'], precioEmpaqueContado: r['precioEmpaqueContadoConItbis'],
    precioEmpaqueCredito: r['precioEmpaqueCreditoConItbis'], precioEmpaqueMayor: r['precioEmpaqueMayorConItbis'], preciosIncluyenItbis: true }),
  campos: [
    { nombre: 'nombre', etiqueta: 'Nombre (máx. 80)', requerido: true, anchoCompleto: true },
    { nombre: 'codigoBarra', etiqueta: 'Código de barras' }, { nombre: 'referencia', etiqueta: 'Referencia' },
    { nombre: 'tipo', etiqueta: 'Tipo', tipo: 'select', requerido: true, opciones: [{ valor: 'B', texto: 'Bien (inventario)' }, { valor: 'S', texto: 'Servicio' }] },
    { nombre: 'departamentoId', etiqueta: 'Departamento', tipo: 'select', requerido: true, opcionesUrl: '/api/v1/catalogos/departamentos' },
    { nombre: 'unidadId', etiqueta: 'Unidad', tipo: 'select', requerido: true, opcionesUrl: '/api/v1/catalogos/unidades' },
    { nombre: 'proveedorId', etiqueta: 'Proveedor habitual', tipo: 'select', opcionesUrl: '/api/v1/catalogos/proveedores' },
    { nombre: 'indicadorItbis', etiqueta: 'ITBIS', tipo: 'select', requerido: true, opciones: [
      { valor: 1, texto: '18 %' }, { valor: 2, texto: '16 %' }, { valor: 3, texto: '0 %' }, { valor: 4, texto: 'Exento' }] },
    { nombre: 'costo', etiqueta: 'Costo por unidad (la de arriba)', tipo: 'numero', requerido: true, min: 0,
      ayuda: 'Por Libra si se lleva en libras: un saco de 100 lb a RD$1,000 es RD$10' },
    { nombre: 'aplicarCostoPromedio', etiqueta: 'Corregir también el costo promedio con este costo (si el artículo aún no tiene compras se corrige solo)',
      tipo: 'check', anchoCompleto: true },
    { nombre: 'preciosIncluyenItbis', etiqueta: 'Los precios que escribo incluyen ITBIS', tipo: 'check', anchoCompleto: true },
    { nombre: 'precioContado', etiqueta: 'Precio contado (por unidad)', tipo: 'numero', requerido: true, min: 0 },
    { nombre: 'precioCredito', etiqueta: 'Precio crédito', tipo: 'numero', min: 0 },
    { nombre: 'precioMayor', etiqueta: 'Precio por mayor', tipo: 'numero', min: 0 },
    { nombre: 'descuentoMaximo', etiqueta: 'Descuento máximo %', tipo: 'numero', min: 0, max: 100 },
    { nombre: 'stockMinimo', etiqueta: 'Stock mínimo', tipo: 'numero', min: 0 },
    { nombre: 'ubicacion', etiqueta: 'Ubicación' },
    { nombre: 'manejaVencimiento', etiqueta: 'Controla fecha de vencimiento (lotes): la compra pedirá la fecha de cada lote', tipo: 'check', anchoCompleto: true },
    // Presentación: se compra/vende también por Saco o Caja; el inventario se lleva en la unidad de arriba
    { nombre: 'unidadEmpaqueId', etiqueta: 'Presentación (opcional)', tipo: 'select', opcionesUrl: '/api/v1/catalogos/unidades' },
    { nombre: 'factorEmpaque', etiqueta: 'Unidades por presentación', tipo: 'numero', min: 0 },
    { nombre: 'codigoBarraEmpaque', etiqueta: 'Código de la presentación' },
    { nombre: 'precioEmpaqueContado', etiqueta: 'Precio presentación contado', tipo: 'numero', min: 0 },
    { nombre: 'precioEmpaqueCredito', etiqueta: 'Precio presentación crédito', tipo: 'numero', min: 0 },
    { nombre: 'precioEmpaqueMayor', etiqueta: 'Precio presentación mayor', tipo: 'numero', min: 0 },
  ],
};

export const GENERALES: { clave: string; config: CatalogoConfig }[] = [
  { clave: 'Departamentos', config: { titulo: 'Departamento', url: '/api/v1/catalogos/departamentos', permisoEscritura: 'CAT_GENERALES', orden: 'nombre',
    columnas: [{ campo: 'nombre', etiqueta: 'Nombre' }, { campo: 'tipo', etiqueta: 'Tipo' }, { campo: 'estado', etiqueta: 'Estado', tipo: 'estado' }],
    nuevo: { tipo: 'I' }, campos: [
      { nombre: 'nombre', etiqueta: 'Nombre', requerido: true },
      { nombre: 'tipo', etiqueta: 'Tipo', tipo: 'select', requerido: true, opciones: [{ valor: 'I', texto: 'Inventario' }, { valor: 'S', texto: 'Servicio' }, { valor: 'E', texto: 'Empleado' }] },
      { nombre: 'cuentaInventario', etiqueta: 'Cuenta inventario' }, { nombre: 'cuentaIngreso', etiqueta: 'Cuenta ingreso' },
      { nombre: 'cuentaCosto', etiqueta: 'Cuenta costo' }] } },
  { clave: 'Almacenes', config: { titulo: 'Almacén', url: '/api/v1/catalogos/almacenes', permisoEscritura: 'CAT_GENERALES', orden: 'nombre',
    columnas: [{ campo: 'nombre', etiqueta: 'Nombre' }, { campo: 'ubicacion', etiqueta: 'Ubicación' }, { campo: 'principal', etiqueta: 'Principal', tipo: 'si-no' }, { campo: 'estado', etiqueta: 'Estado', tipo: 'estado' }],
    nuevo: { principal: false }, campos: [
      { nombre: 'nombre', etiqueta: 'Nombre', requerido: true }, { nombre: 'ubicacion', etiqueta: 'Ubicación' },
      { nombre: 'principal', etiqueta: 'Almacén principal (por defecto en ventas)', tipo: 'check', anchoCompleto: true }] } },
  { clave: 'Unidades', config: { titulo: 'Unidad', url: '/api/v1/catalogos/unidades', permisoEscritura: 'CAT_GENERALES', orden: 'nombre',
    columnas: [{ campo: 'nombre', etiqueta: 'Nombre' }, { campo: 'abreviatura', etiqueta: 'Abrev.' }, { campo: 'estado', etiqueta: 'Estado', tipo: 'estado' }],
    nuevo: {}, campos: [{ nombre: 'nombre', etiqueta: 'Nombre', requerido: true }, { nombre: 'abreviatura', etiqueta: 'Abreviatura' }] } },
  { clave: 'Vendedores', config: { titulo: 'Vendedor', url: '/api/v1/catalogos/vendedores', permisoEscritura: 'CAT_GENERALES', orden: 'nombre',
    columnas: [{ campo: 'nombre', etiqueta: 'Nombre' }, { campo: 'rncCedula', etiqueta: 'Cédula' }, { campo: 'comision', etiqueta: 'Comisión %', tipo: 'numero' }, { campo: 'estado', etiqueta: 'Estado', tipo: 'estado' }],
    nuevo: { comision: 0 }, campos: [
      { nombre: 'nombre', etiqueta: 'Nombre', requerido: true }, { nombre: 'rncCedula', etiqueta: 'Cédula', ayuda: RNC_AYUDA },
      { nombre: 'comision', etiqueta: 'Comisión %', tipo: 'numero', requerido: true, min: 0, max: 100 },
      { nombre: 'telefono', etiqueta: 'Teléfono' }, { nombre: 'correo', etiqueta: 'Correo', tipo: 'email' },
      { nombre: 'direccion', etiqueta: 'Dirección', anchoCompleto: true }] } },
  { clave: 'Ciudades', config: { titulo: 'Ciudad', url: '/api/v1/catalogos/ciudades', permisoEscritura: 'CAT_GENERALES', orden: 'nombre',
    columnas: [{ campo: 'nombre', etiqueta: 'Nombre' }, { campo: 'provincia', etiqueta: 'Provincia' }, { campo: 'estado', etiqueta: 'Estado', tipo: 'estado' }],
    nuevo: {}, campos: [{ nombre: 'nombre', etiqueta: 'Nombre', requerido: true }, { nombre: 'provincia', etiqueta: 'Provincia' }] } },
  { clave: 'Zonas', config: { titulo: 'Zona', url: '/api/v1/catalogos/zonas', permisoEscritura: 'CAT_GENERALES', orden: 'nombre',
    columnas: [{ campo: 'nombre', etiqueta: 'Nombre' }, { campo: 'estado', etiqueta: 'Estado', tipo: 'estado' }],
    nuevo: {}, campos: [{ nombre: 'nombre', etiqueta: 'Nombre', requerido: true }] } },
  { clave: 'Turnos', config: { titulo: 'Turno', url: '/api/v1/catalogos/turnos', permisoEscritura: 'CAT_GENERALES', orden: 'nombre',
    columnas: [{ campo: 'nombre', etiqueta: 'Nombre' }, { campo: 'estado', etiqueta: 'Estado', tipo: 'estado' }],
    nuevo: {}, campos: [{ nombre: 'nombre', etiqueta: 'Nombre', requerido: true }] } },
];
