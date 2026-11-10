import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { FormControl, FormGroup, FormsModule, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonModule } from '@angular/material/button';
import { forkJoin, map, of } from 'rxjs';
import type { Pagina } from '../api/tipos';
import { AuthService } from '../core/auth.service';
import { Notificacion } from '../core/notificacion.service';
import { ConfirmarDialog } from './confirmar-dialog';
import { params } from './http';

type Registro = Record<string, unknown> & { id?: number; estado?: string };

export interface Opcion { valor: unknown; texto: string; }

export interface Campo {
  nombre: string;
  etiqueta: string;
  tipo?: 'texto' | 'numero' | 'email' | 'select' | 'check' | 'fecha';
  requerido?: boolean;
  /** Opciones fijas del select. */
  opciones?: Opcion[];
  /** O cargadas de un catálogo (id -> nombre). */
  opcionesUrl?: string;
  anchoCompleto?: boolean;
  min?: number;
  max?: number;
  patron?: string;
  ayuda?: string;
}

export interface Columna { campo: string; etiqueta: string; tipo?: 'moneda' | 'numero' | 'estado' | 'si-no'; }

export interface CatalogoConfig {
  titulo: string;
  url: string;
  columnas: Columna[];
  campos: Campo[];
  /** Valores por defecto al crear. */
  nuevo: Registro;
  orden?: string;
  /** Opción de menú que permite crear/editar/desactivar (el backend la valida). */
  permisoEscritura: string;
  /** Transforma el registro antes de cargarlo en el formulario de edición. */
  alEditar?: (r: Registro) => Registro;
}

/** Listado paginado con búsqueda y alta/edición/desactivación de un catálogo del backend. */
@Component({
  selector: 'app-catalogo-crud',
  imports: [CurrencyPipe, DecimalPipe, FormsModule, MatTableModule, MatPaginatorModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  template: `
    <div class="fila">
      <mat-form-field style="flex:1 1 280px"><mat-label>Buscar</mat-label>
        <input matInput [(ngModel)]="q" (keyup.enter)="cargar(0)" /></mat-form-field>
      <button mat-stroked-button (click)="cargar(0)">Buscar</button>
      <span class="espaciador"></span>
      @if (puedeEditar()) { <button mat-flat-button (click)="editar(null)"><span class="material-symbols-outlined">add</span> Nuevo</button> }
    </div>
    <div class="tabla-contenedor">
      <table mat-table [dataSource]="pagina()?.content ?? []">
        @for (c of config().columnas; track c.campo) {
          <ng-container [matColumnDef]="c.campo">
            <th mat-header-cell *matHeaderCellDef [class.num]="c.tipo === 'moneda' || c.tipo === 'numero'">{{ c.etiqueta }}</th>
            <td mat-cell *matCellDef="let r" [class.num]="c.tipo === 'moneda' || c.tipo === 'numero'">
              @switch (c.tipo) {
                @case ('moneda') { {{ $any(r[c.campo]) | currency }} }
                @case ('numero') { {{ $any(r[c.campo]) | number: '1.0-3' }} }
                @case ('estado') { <span class="chip" [class.ok]="r[c.campo] === 'A'">{{ r[c.campo] === 'A' ? 'Activo' : 'Inactivo' }}</span> }
                @case ('si-no') { {{ r[c.campo] ? 'Sí' : 'No' }} }
                @default { {{ r[c.campo] }} }
              }
            </td>
          </ng-container>
        }
        <ng-container matColumnDef="acciones"><th mat-header-cell *matHeaderCellDef></th>
          <td mat-cell *matCellDef="let r" style="white-space:nowrap" (click)="$event.stopPropagation()">
            @for (a of acciones(); track a.etiqueta) {
              <button mat-button (click)="accion.emit({ accion: a.etiqueta, registro: r })">{{ a.etiqueta }}</button>
            }
          </td></ng-container>
        <tr mat-header-row *matHeaderRowDef="columnas()"></tr>
        <tr mat-row *matRowDef="let r; columns: columnas()" [class.clickable]="puedeEditar()" (click)="puedeEditar() && editar(r)"></tr>
        <tr *matNoDataRow><td [attr.colspan]="columnas().length" class="muted" style="padding:24px">Sin registros</td></tr>
      </table>
    </div>
    <mat-paginator [length]="pagina()?.totalElements ?? 0" [pageSize]="20" [pageIndex]="pagina()?.number ?? 0" (page)="cambiar($event)" />`,
})
export class CatalogoCrud {
  readonly config = input.required<CatalogoConfig>();
  readonly acciones = input<{ etiqueta: string }[]>([]);
  readonly accion = output<{ accion: string; registro: Registro }>();

  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  private readonly auth = inject(AuthService);
  protected readonly pagina = signal<Pagina<Registro> | null>(null);
  protected readonly puedeEditar = computed(() => this.auth.puede(this.config().permisoEscritura));
  protected readonly columnas = computed(() => [...this.config().columnas.map((c) => c.campo), ...(this.acciones().length ? ['acciones'] : [])]);
  protected q = '';

  constructor() {
    effect(() => { this.config(); this.q = ''; this.cargar(0); });
  }

  cargar(page: number): void {
    const c = this.config();
    this.http.get<Pagina<Registro>>(c.url, { params: params({ q: this.q, page, size: 20, sort: c.orden ?? 'id' }) })
      .subscribe({ next: (p) => this.pagina.set(p), error: (e) => this.notif.error(e) });
  }

  protected cambiar(e: PageEvent): void { this.cargar(e.pageIndex); }

  protected editar(r: Registro | null): void {
    this.dialog.open(CatalogoFormDialog, { data: { config: this.config(), registro: r }, width: '760px', maxWidth: '95vw' })
      .afterClosed().subscribe((cambio) => { if (cambio) this.cargar(this.pagina()?.number ?? 0); });
  }
}

/** Formulario generado a partir de la configuración de campos. */
@Component({
  selector: 'app-catalogo-form-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatCheckboxModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>{{ registro ? 'Editar' : 'Nuevo' }} · {{ config.titulo }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="grid-form">
        @for (c of config.campos; track c.nombre) {
          @switch (c.tipo) {
            @case ('select') {
              <mat-form-field [class.ancho-completo]="c.anchoCompleto"><mat-label>{{ c.etiqueta }}</mat-label>
                <mat-select [formControlName]="c.nombre">
                  @if (!c.requerido) { <mat-option [value]="null">—</mat-option> }
                  @for (o of opciones()[c.nombre] ?? []; track o.valor) { <mat-option [value]="o.valor">{{ o.texto }}</mat-option> }
                </mat-select>
                @if (c.ayuda) { <mat-hint>{{ c.ayuda }}</mat-hint> }
              </mat-form-field>
            }
            @case ('check') { <mat-checkbox [formControlName]="c.nombre" [class.ancho-completo]="c.anchoCompleto">{{ c.etiqueta }}</mat-checkbox> }
            @default {
              <mat-form-field [class.ancho-completo]="c.anchoCompleto"><mat-label>{{ c.etiqueta }}</mat-label>
                <input matInput [formControlName]="c.nombre" [type]="c.tipo === 'numero' ? 'number' : c.tipo === 'email' ? 'email' : c.tipo === 'fecha' ? 'date' : 'text'" />
                @if (c.ayuda) { <mat-hint>{{ c.ayuda }}</mat-hint> }
                @if (errores()[c.nombre]) { <mat-error>{{ errores()[c.nombre] }}</mat-error> }
              </mat-form-field>
            }
          }
        }
      </form>
      @if (error()) { <p class="error-texto">{{ error() }}</p> }
    </mat-dialog-content>
    <mat-dialog-actions>
      @if (registro && registro.estado === 'A') { <button mat-button (click)="desactivar()" style="color: var(--mat-sys-error)">Desactivar</button> }
      <span class="espaciador"></span>
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="form.invalid || guardando()" (click)="guardar()">Guardar</button>
    </mat-dialog-actions>`,
})
export class CatalogoFormDialog {
  private readonly datos = inject<{ config: CatalogoConfig; registro: Registro | null }>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<CatalogoFormDialog>);
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  protected readonly config = this.datos.config;
  protected readonly registro = this.datos.registro;
  protected readonly opciones = signal<Record<string, Opcion[]>>({});
  protected readonly errores = signal<Record<string, string>>({});
  protected readonly error = signal<string | null>(null);
  protected readonly guardando = signal(false);
  protected readonly form = new FormGroup<Record<string, FormControl>>({});

  constructor() {
    const base = { ...this.config.nuevo, ...(this.registro ? (this.config.alEditar?.(this.registro) ?? this.registro) : {}) };
    for (const c of this.config.campos) {
      const v: ValidatorFn[] = [];
      if (c.requerido) v.push(Validators.required);
      if (c.min !== undefined) v.push(Validators.min(c.min));
      if (c.max !== undefined) v.push(Validators.max(c.max));
      if (c.patron) v.push(Validators.pattern(c.patron));
      if (c.tipo === 'email') v.push(Validators.email);
      this.form.addControl(c.nombre, new FormControl(base[c.nombre] ?? (c.tipo === 'check' ? false : null), v));
    }
    const peticiones = this.config.campos.filter((c) => c.opcionesUrl).map((c) =>
      this.http.get<Pagina<Registro>>(c.opcionesUrl!, { params: params({ size: 500, sort: 'nombre' }) }).pipe(
        map((p): [string, Opcion[]] => [c.nombre, p.content.filter((r) => r.estado !== 'I').map((r) => ({ valor: r.id, texto: String(r['nombre']) }))])));
    (peticiones.length ? forkJoin(peticiones) : of([] as [string, Opcion[]][])).subscribe((res) => {
      const o: Record<string, Opcion[]> = {};
      for (const c of this.config.campos) if (c.opciones) o[c.nombre] = c.opciones;
      for (const [nombre, lista] of res) o[nombre] = lista;
      this.opciones.set(o);
    });
  }

  protected guardar(): void {
    const valores: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(this.form.getRawValue())) valores[k] = v === '' ? null : v;
    this.guardando.set(true);
    this.error.set(null);
    const req = this.registro ? this.http.put(`${this.config.url}/${this.registro.id}`, valores) : this.http.post(this.config.url, valores);
    req.subscribe({
      next: () => { this.notif.ok('Guardado'); this.ref.close(true); },
      error: (e) => {
        this.guardando.set(false);
        const errs = e?.error?.errores as Record<string, string> | undefined;
        if (errs) { this.errores.set(errs); for (const k of Object.keys(errs)) this.form.get(k)?.setErrors({ servidor: true }); }
        this.error.set(e?.error?.detail ?? 'No se pudo guardar');
      },
    });
  }

  protected desactivar(): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: 'Desactivar', mensaje: 'El registro dejará de estar disponible para nuevas operaciones.', accion: 'Desactivar', peligro: true } })
      .afterClosed().subscribe((ok) => {
        if (!ok) return;
        this.http.delete(`${this.config.url}/${this.registro!.id}`).subscribe({
          next: () => { this.notif.ok('Desactivado'); this.ref.close(true); },
          error: (e) => this.notif.error(e),
        });
      });
  }
}
