import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import type { S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { Notificacion } from '../../core/notificacion.service';
import { CatalogoConfig, CatalogoCrud } from '../../shared/catalogo-crud';
import { CuentasContables, TIPOS_CUENTA } from './cuentas-contables';

const CATALOGO: CatalogoConfig = {
  titulo: 'Cuenta contable', url: '/api/v1/contabilidad/cuentas', permisoEscritura: 'CONT_CATALOGO', orden: 'codigo',
  columnas: [{ campo: 'codigo', etiqueta: 'Código' }, { campo: 'nombre', etiqueta: 'Nombre' }, { campo: 'tipo', etiqueta: 'Tipo' },
    { campo: 'padreCodigo', etiqueta: 'Grupo' }, { campo: 'nivel', etiqueta: 'Nivel', tipo: 'numero' },
    { campo: 'aceptaMovimiento', etiqueta: 'Detalle', tipo: 'si-no' }, { campo: 'estado', etiqueta: 'Estado', tipo: 'estado' }],
  nuevo: { tipo: 'ACTIVO', aceptaMovimiento: true },
  campos: [
    { nombre: 'codigo', etiqueta: 'Código', requerido: true, patron: '[0-9A-Za-z.-]+' },
    { nombre: 'nombre', etiqueta: 'Nombre', requerido: true },
    { nombre: 'tipo', etiqueta: 'Tipo', tipo: 'select', requerido: true, opciones: TIPOS_CUENTA },
    { nombre: 'padreCodigo', etiqueta: 'Código de la cuenta de grupo', ayuda: 'Vacío = cuenta de primer nivel' },
    { nombre: 'aceptaMovimiento', etiqueta: 'Cuenta de detalle (recibe movimientos)', tipo: 'check', anchoCompleto: true },
  ],
};

/** Catálogo de cuentas y cuentas por defecto de los asientos automáticos. */
@Component({
  selector: 'app-catalogo-contable',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatTabsModule, CatalogoCrud],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Catálogo de cuentas</h1></div>
      @if (vacio() && puedeEditar()) {
        <div class="tarjeta aviso">
          <p>La compañía aún no tiene catálogo. Puede cargar un catálogo base (activos, pasivos, capital, ingresos, costos y gastos)
            con las cuentas por defecto ya asignadas, y luego ajustarlo.</p>
          <button mat-flat-button (click)="plantilla()">Cargar catálogo base</button>
        </div>
      }
      <mat-tab-group>
        <mat-tab label="Cuentas">
          <div class="tarjeta">@if (mostrar()) { <app-catalogo-crud [config]="config" /> }</div>
        </mat-tab>
        <mat-tab label="Cuentas por defecto">
          <div class="tarjeta">
            <div class="fila">
              <mat-form-field><mat-label>Inicio de la contabilidad</mat-label>
                <input matInput type="date" [(ngModel)]="fechaInicio" [disabled]="!auth.esAdmin()" />
                <mat-hint>Los documentos anteriores no generan asientos</mat-hint></mat-form-field>
              @if (auth.esAdmin()) { <button mat-stroked-button (click)="guardarInicio()">Guardar fecha</button> }
            </div>
            <p class="ayuda">Se usan en los asientos automáticos cuando el departamento (ingreso, costo, inventario), el cliente,
              el proveedor o la cuenta bancaria no indican su propia cuenta contable.</p>
            <div class="grid-config">
              @for (i of configuracion(); track i.clave) {
                <mat-form-field>
                  <mat-label>{{ i.descripcion }}</mat-label>
                  <mat-select [(ngModel)]="cambios[i.clave!]" [disabled]="!puedeEditar()">
                    <mat-option [value]="''">— Sin asignar —</mat-option>
                    @for (c of cuentas.detalle(); track c.id) { <mat-option [value]="c.codigo">{{ c.codigo }} · {{ c.nombre }}</mat-option> }
                  </mat-select>
                  @if (!i.codigo) { <mat-hint class="falta">Sin asignar</mat-hint> }
                </mat-form-field>
              }
            </div>
            @if (puedeEditar()) { <div class="fila"><span class="espaciador"></span><button mat-flat-button (click)="guardar()">Guardar</button></div> }
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>`,
  styles: `.grid-config { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 18px 16px; align-items: start; }
    .ayuda { color: var(--mat-sys-on-surface-variant); } .aviso { margin-bottom: 16px; } .falta { color: var(--mat-sys-error); }`,
})
export class Catalogo {
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  protected readonly auth = inject(AuthService);
  protected readonly cuentas = inject(CuentasContables);
  protected readonly config = CATALOGO;
  protected readonly configuracion = signal<S['ConfigContableItem'][]>([]);
  protected readonly vacio = computed(() => this.cuentas.todas().length === 0);
  protected readonly puedeEditar = computed(() => this.auth.puede('CONT_CATALOGO'));
  protected readonly mostrar = signal(true);
  protected cambios: Record<string, string> = {};
  protected fechaInicio = '';

  constructor() {
    this.cuentas.cargar();
    this.cargarConfig();
  }

  protected guardarInicio(): void {
    this.http.put<S['ConfigContableInicio']>('/api/v1/contabilidad/configuracion/inicio', { fechaInicio: this.fechaInicio || null }).subscribe({
      next: () => this.notif.ok('Fecha de inicio guardada'), error: (e) => this.notif.error(e) });
  }

  private cargarConfig(): void {
    this.http.get<S['ConfigContableInicio']>('/api/v1/contabilidad/configuracion/inicio').subscribe((i) => this.fechaInicio = i.fechaInicio ?? '');
    this.http.get<S['ConfigContableItem'][]>('/api/v1/contabilidad/configuracion').subscribe((c) => {
      this.configuracion.set(c);
      this.cambios = Object.fromEntries(c.map((i) => [i.clave!, i.codigo ?? '']));
    });
  }

  protected plantilla(): void {
    this.http.post<{ cuentas: number }>('/api/v1/contabilidad/cuentas/plantilla', {}).subscribe({
      next: (r) => {
        this.notif.ok(`Catálogo base cargado (${r.cuentas} cuentas)`);
        this.cuentas.cargar(); this.cargarConfig();
        // Recrea el listado para que vuelva a consultar
        this.mostrar.set(false); setTimeout(() => this.mostrar.set(true));
      },
      error: (e) => this.notif.error(e),
    });
  }

  protected guardar(): void {
    const req: S['ConfigContableCambio'][] = Object.entries(this.cambios).map(([clave, codigo]) => ({ clave: clave as S['ConfigContableCambio']['clave'], codigo }));
    this.http.put<S['ConfigContableItem'][]>('/api/v1/contabilidad/configuracion', req).subscribe({
      next: (c) => { this.configuracion.set(c); this.notif.ok('Cuentas por defecto guardadas'); }, error: (e) => this.notif.error(e) });
  }
}
