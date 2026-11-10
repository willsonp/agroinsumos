import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import JsBarcode from 'jsbarcode';
import type { Pagina, S } from '../../api/tipos';
import { Notificacion } from '../../core/notificacion.service';
import { ArticuloBuscador } from '../../shared/articulo-buscador';
import { CatalogoConfig, CatalogoCrud } from '../../shared/catalogo-crud';
import { descargar, params } from '../../shared/http';

type Etiqueta = S['Etiqueta'];
type Diseno = S['GeneradorZplDiseno'];
interface Item { articulo: S['ArticuloResponse']; cantidad: number; }

const IMPRESORAS: CatalogoConfig = {
  titulo: 'Impresora de etiquetas', url: '/api/v1/inventario/impresoras', permisoEscritura: 'INV_ETIQUETAS', orden: 'nombre',
  columnas: [{ campo: 'nombre', etiqueta: 'Nombre' }, { campo: 'host', etiqueta: 'IP / equipo' }, { campo: 'puerto', etiqueta: 'Puerto' },
    { campo: 'dpi', etiqueta: 'DPI' }, { campo: 'anchoMm', etiqueta: 'Ancho mm', tipo: 'numero' }, { campo: 'altoMm', etiqueta: 'Alto mm', tipo: 'numero' },
    { campo: 'estado', etiqueta: 'Estado', tipo: 'estado' }],
  nuevo: { puerto: 9100, dpi: 203, anchoMm: 50.8, altoMm: 25.4 },
  campos: [
    { nombre: 'nombre', etiqueta: 'Nombre', requerido: true },
    { nombre: 'host', etiqueta: 'IP o nombre de la impresora', requerido: true, ayuda: 'Impresora Zebra/ZPL en red, p.ej. 192.168.1.50' },
    { nombre: 'puerto', etiqueta: 'Puerto RAW', tipo: 'numero', min: 9100, max: 9109 },
    { nombre: 'dpi', etiqueta: 'Resolución', tipo: 'select', requerido: true, opciones: [
      { valor: 203, texto: '203 dpi (8 puntos/mm)' }, { valor: 300, texto: '300 dpi (12 puntos/mm)' }, { valor: 600, texto: '600 dpi (24 puntos/mm)' }] },
    { nombre: 'anchoMm', etiqueta: 'Ancho de etiqueta (mm)', tipo: 'numero', requerido: true },
    { nombre: 'altoMm', etiqueta: 'Alto de etiqueta (mm)', tipo: 'numero', requerido: true },
  ],
};

/** Impresión de etiquetas con código de barras en ZPL, con vista previa y cantidad por artículo. */
@Component({
  selector: 'app-etiquetas',
  imports: [DecimalPipe, FormsModule, MatButtonModule, MatButtonToggleModule, MatCheckboxModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatTabsModule, ArticuloBuscador, CatalogoCrud],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Imprimir etiquetas</h1></div>
      <mat-tab-group (selectedIndexChange)="$event === 0 && cargarImpresoras()">
        <mat-tab label="Etiquetas">
          <div class="tarjeta">
            <h2>1. Artículos y cantidades</h2>
            <app-articulo-buscador (elegido)="agregar($event)" />
            <table class="tabla">
              <tr><th>Artículo</th><th>Código</th><th class="num">Existencia</th><th class="num">Etiquetas</th><th></th></tr>
              @for (it of items(); track it.articulo.id; let i = $index) {
                <tr><td>{{ it.articulo.nombre }}</td><td>{{ it.articulo.codigoBarra || it.articulo.referencia || '(número interno)' }}</td>
                  <td class="num">{{ it.articulo.existencia | number: '1.0-3' }}</td>
                  <td class="num"><input class="celda" type="number" min="1" max="1000" [ngModel]="it.cantidad" (ngModelChange)="fijar(i, +$event)"
                    [attr.aria-label]="'Cantidad de etiquetas de ' + it.articulo.nombre" /></td>
                  <td><button mat-button (click)="fijar(i, existencia(it))">= existencia</button>
                    <button mat-icon-button (click)="quitar(i)" aria-label="Quitar"><span class="material-symbols-outlined">delete</span></button></td></tr>
              } @empty { <tr><td colspan="5" class="muted">Busque y agregue artículos</td></tr> }
            </table>
            <p>Total: <strong>{{ total() }}</strong> etiqueta(s)</p>
          </div>

          <div class="tarjeta">
            <h2>2. Tamaño, resolución y contenido</h2>
            <div class="fila">
              <mat-form-field style="min-width:280px"><mat-label>Tamaño</mat-label>
                <mat-select [(ngModel)]="tamano" (selectionChange)="cambiarTamano()">
                  @for (t of tamanos(); track t.nombre) { <mat-option [value]="t.nombre">{{ t.nombre }}</mat-option> }
                  <mat-option value="PERSONALIZADO">Personalizado…</mat-option>
                </mat-select></mat-form-field>
              <mat-form-field style="width:120px"><mat-label>Ancho (mm)</mat-label><input matInput type="number" [(ngModel)]="anchoMm" [disabled]="tamano !== 'PERSONALIZADO'" /></mat-form-field>
              <mat-form-field style="width:120px"><mat-label>Alto (mm)</mat-label><input matInput type="number" [(ngModel)]="altoMm" [disabled]="tamano !== 'PERSONALIZADO'" /></mat-form-field>
              <mat-button-toggle-group [(ngModel)]="dpi" aria-label="Resolución de la impresora">
                <mat-button-toggle [value]="203">203 dpi</mat-button-toggle>
                <mat-button-toggle [value]="300">300 dpi</mat-button-toggle>
                <mat-button-toggle [value]="600">600 dpi</mat-button-toggle>
              </mat-button-toggle-group>
            </div>
            <div class="fila" style="margin-top:8px">
              <mat-form-field><mat-label>Precio en la etiqueta</mat-label><mat-select [(ngModel)]="precio">
                <mat-option [value]="null">Sin precio</mat-option><mat-option value="CONTADO">Contado (con ITBIS)</mat-option>
                <mat-option value="CREDITO">Crédito (con ITBIS)</mat-option><mat-option value="MAYOR">Por mayor (con ITBIS)</mat-option></mat-select></mat-form-field>
              <mat-checkbox [(ngModel)]="mostrarNombre">Nombre del artículo</mat-checkbox>
              <mat-checkbox [(ngModel)]="mostrarCodigo">Código legible bajo las barras</mat-checkbox>
              <span class="espaciador"></span>
              <button mat-flat-button [disabled]="!items().length" (click)="vistaPrevia()"><span class="material-symbols-outlined">visibility</span> Vista previa</button>
            </div>
          </div>

          @if (resultado(); as r) {
            <div class="tarjeta">
              <h2>3. Vista previa e impresión</h2>
              <div class="fila">
                <mat-checkbox [(ngModel)]="tamanoReal">Tamaño real en pantalla (aprox.)</mat-checkbox>
                <span class="muted">{{ anchoMm }} × {{ altoMm }} mm · {{ dpi }} dpi · {{ r.etiquetas![0].diseno!.anchoPuntos }} × {{ r.etiquetas![0].diseno!.altoPuntos }} puntos</span>
              </div>
              <div class="previas">
                @for (e of r.etiquetas; track $index) {
                  <figure>
                    <img [src]="imagenes()[$index]" [alt]="'Vista previa de la etiqueta de ' + e.nombre" [style.width.px]="anchoPantalla()" />
                    <figcaption>{{ e.nombre }} · <strong>× {{ e.cantidad }}</strong> · {{ e.simbologia }}</figcaption>
                  </figure>
                }
              </div>
              <p class="muted">La vista previa usa una fuente parecida a la de la impresora; el texto impreso puede variar ligeramente.</p>
              <div class="fila">
                <button mat-stroked-button (click)="verZpl.set(!verZpl())">{{ verZpl() ? 'Ocultar' : 'Ver' }} código ZPL</button>
                <button mat-stroked-button (click)="descargarZpl()"><span class="material-symbols-outlined">download</span> Descargar .zpl</button>
                <span class="espaciador"></span>
                <mat-form-field style="min-width:240px"><mat-label>Impresora</mat-label><mat-select [(ngModel)]="impresoraId">
                  @for (i of impresoras(); track i.id) { <mat-option [value]="i.id">{{ i.nombre }} · {{ i.host }} · {{ i.dpi }} dpi</mat-option> }
                  @empty { <mat-option disabled>Registre una impresora en la pestaña Impresoras</mat-option> }
                </mat-select></mat-form-field>
                <button mat-flat-button [disabled]="!impresoraId" (click)="imprimir()"><span class="material-symbols-outlined">print</span> Imprimir {{ r.totalEtiquetas }}</button>
              </div>
              @if (dpiDistinto()) { <p class="error-texto">La impresora elegida es de {{ impresoraSel()?.dpi }} dpi y la vista previa es a {{ dpi }} dpi.</p> }
              @if (verZpl()) { <textarea class="zpl" readonly [value]="r.zpl"></textarea> }
              <p class="muted">Sin impresora de red: descargue el .zpl y envíelo con Zebra Setup Utilities o, en Windows, con
                <code>copy /b etiquetas.zpl \\\\equipo\\ZebraCompartida</code>.</p>
            </div>
          }
        </mat-tab>
        <mat-tab label="Impresoras">
          <div class="tarjeta"><app-catalogo-crud [config]="configImpresoras" /></div>
        </mat-tab>
      </mat-tab-group>
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .tabla .num { text-align: right; } .celda { width: 90px; padding: 6px; text-align: right; }
    .previas { display: flex; flex-wrap: wrap; gap: 16px; margin: 12px 0; }
    figure { margin: 0; } figure img { display: block; border: 1px solid var(--mat-sys-outline); border-radius: 6px; background: #fff;
      box-shadow: 0 1px 3px rgba(0,0,0,.2); max-width: 100%; }
    figcaption { font-size: .85em; margin-top: 4px; color: var(--mat-sys-on-surface-variant); }
    .zpl { width: 100%; min-height: 160px; font-family: monospace; font-size: 12px; }`,
})
export class Etiquetas {
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  protected readonly configImpresoras = IMPRESORAS;
  protected readonly items = signal<Item[]>([]);
  protected readonly tamanos = signal<S['EtiquetaTamano'][]>([]);
  protected readonly impresoras = signal<S['ImpresoraEtiquetaResponse'][]>([]);
  protected readonly resultado = signal<S['EtiquetaResponse'] | null>(null);
  protected readonly imagenes = signal<string[]>([]);
  protected readonly verZpl = signal(false);
  protected readonly total = computed(() => this.items().reduce((s, i) => s + i.cantidad, 0));
  protected tamano = '2 × 1 pulg (51 × 25 mm)';
  protected anchoMm = 50.8;
  protected altoMm = 25.4;
  protected dpi = 203;
  protected precio: 'CONTADO' | 'CREDITO' | 'MAYOR' | null = 'CONTADO';
  protected mostrarNombre = true;
  protected mostrarCodigo = true;
  protected tamanoReal = false;
  protected impresoraId: number | null = null;

  constructor() {
    this.http.get<S['EtiquetaTamano'][]>('/api/v1/inventario/etiquetas/tamanos').subscribe((t) => this.tamanos.set(t));
    this.cargarImpresoras();
  }

  protected cargarImpresoras(): void {
    this.http.get<Pagina<S['ImpresoraEtiquetaResponse']>>('/api/v1/inventario/impresoras', { params: params({ size: 100 }) })
      .subscribe((p) => this.impresoras.set(p.content.filter((i) => i.estado === 'A')));
  }

  protected impresoraSel() { return this.impresoras().find((i) => i.id === this.impresoraId) ?? null; }
  protected dpiDistinto(): boolean { const i = this.impresoraSel(); return !!i && i.dpi !== this.dpi; }

  /** Ancho en pantalla: tamaño real (1 mm ≈ 3.78 px) o ajustado a 320 px. */
  protected anchoPantalla(): number { return this.tamanoReal ? Math.round(this.anchoMm * 3.78) : Math.min(320, Math.round(this.anchoMm * 3.78 * 1.6)); }

  protected cambiarTamano(): void {
    const t = this.tamanos().find((x) => x.nombre === this.tamano);
    if (t) { this.anchoMm = t.anchoMm!; this.altoMm = t.altoMm!; }
  }

  protected agregar(a: S['ArticuloResponse']): void {
    if (this.items().some((i) => i.articulo.id === a.id)) { this.notif.error(null, `'${a.nombre}' ya está en la lista`); return; }
    this.items.set([...this.items(), { articulo: a, cantidad: 1 }]);
  }
  protected existencia(it: Item): number { return Math.max(1, Math.min(1000, Math.floor(it.articulo.existencia ?? 1))); }
  protected fijar(i: number, v: number): void { this.items.set(this.items().map((x, j) => (j === i ? { ...x, cantidad: Math.max(1, Math.min(1000, v || 1)) } : x))); }
  protected quitar(i: number): void { this.items.set(this.items().filter((_, j) => j !== i)); }

  private peticion(): S['EtiquetaRequest'] {
    return { anchoMm: this.anchoMm, altoMm: this.altoMm, dpi: this.dpi, precio: this.precio ?? undefined, mostrarNombre: this.mostrarNombre,
      mostrarCodigo: this.mostrarCodigo, items: this.items().map((i) => ({ articuloId: i.articulo.id!, cantidad: i.cantidad })) };
  }

  protected vistaPrevia(): void {
    this.http.post<S['EtiquetaResponse']>('/api/v1/inventario/etiquetas/vista-previa', this.peticion()).subscribe({
      next: (r) => { this.resultado.set(r); this.imagenes.set((r.etiquetas ?? []).map((e) => this.dibujar(e))); },
      error: (e) => { this.resultado.set(null); this.notif.error(e); } });
  }

  /** Dibuja el diseño (en puntos de impresora) en un canvas y lo devuelve como PNG. */
  private dibujar(e: Etiqueta): string {
    const d = e.diseno as Diseno;
    const c = document.createElement('canvas');
    c.width = d.anchoPuntos!; c.height = d.altoPuntos!;
    const g = c.getContext('2d')!;
    g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height);
    g.fillStyle = '#000'; g.textAlign = 'center'; g.textBaseline = 'top';
    for (const el of d.elementos ?? []) {
      if (el.tipo === 'TEXTO') {
        g.font = `bold ${el.alto}px "Arial Narrow", "Roboto Condensed", Arial, sans-serif`;
        g.fillText(el.texto ?? '', el.x! + el.ancho! / 2, el.y!, el.ancho);
      } else {
        const b = document.createElement('canvas');
        try {
          JsBarcode(b, el.texto ?? '', { format: el.simbologia === 'EAN13' ? 'EAN13' : el.simbologia === 'UPCA' ? 'UPC' : 'CODE128',
            width: el.modulo, height: el.alto, margin: 0, displayValue: false, flat: true });
          g.drawImage(b, el.x!, el.y!);
        } catch {
          g.strokeRect(el.x!, el.y!, el.ancho!, el.alto!);
        }
      }
    }
    return c.toDataURL('image/png');
  }

  protected descargarZpl(): void {
    this.http.post('/api/v1/inventario/etiquetas/zpl', this.peticion(), { responseType: 'blob' })
      .subscribe({ next: (b) => descargar(b, 'etiquetas.zpl'), error: (e) => this.notif.error(e) });
  }

  protected imprimir(): void {
    this.http.post<S['EtiquetaImpresion']>(`/api/v1/inventario/etiquetas/imprimir/${this.impresoraId}`, this.peticion()).subscribe({
      next: (r) => this.notif.ok(`${r.totalEtiquetas} etiqueta(s) enviadas a ${r.impresora}`), error: (e) => this.notif.error(e) });
  }
}
