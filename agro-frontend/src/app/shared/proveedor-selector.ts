import { Component, ElementRef, forwardRef, inject, input, output, viewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ControlValueAccessor, FormControl, NG_VALUE_ACCESSOR, ReactiveFormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatAutocompleteModule, MatAutocompleteTrigger } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { catchError, debounceTime, distinctUntilChanged, filter, map, of, switchMap } from 'rxjs';
import type { Pagina, S } from '../api/tipos';
import { params } from './http';
import { ProveedorBusquedaDialog } from './proveedor-busqueda-dialog';

type Proveedor = S['ProveedorResponse'];

/**
 * Selector de suplidor (como el de clientes): autocompleta por nombre o RNC y la lupa (o F2) abre la ventana de
 * búsqueda. El valor (ngModel) es el id del suplidor; vacío = ninguno ("Todos" en filtros).
 */
@Component({
  selector: 'app-proveedor-selector',
  imports: [ReactiveFormsModule, MatAutocompleteModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatTooltipModule],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => ProveedorSelector), multi: true }],
  template: `
    <mat-form-field style="width:100%">
      <mat-label>{{ etiqueta() }}</mat-label>
      <span matTextPrefix class="material-symbols-outlined" style="margin-right:6px">local_shipping</span>
      <input #campo matInput [formControl]="texto" [matAutocomplete]="auto" [placeholder]="vacio() || 'Nombre o RNC'"
             [required]="requerido()" (keydown.f2)="buscar($event)" (blur)="alSalir()" />
      <button mat-icon-button matSuffix type="button" (click)="buscar($event)" [disabled]="texto.disabled" matTooltip="Buscar suplidor (F2)"
              aria-label="Buscar suplidor"><span class="material-symbols-outlined">search</span></button>
      <mat-autocomplete #auto [displayWith]="mostrar" (optionSelected)="fijar($event.option.value)">
        @for (p of opciones(); track p.id) {
          <mat-option [value]="p">{{ p.nombre }} @if (p.rncCedula) { <span class="muted">· {{ p.rncCedula }}</span> }</mat-option>
        }
      </mat-autocomplete>
    </mat-form-field>`,
  styles: `:host { display: block; flex: 1 1 260px; min-width: 0; }`,
})
export class ProveedorSelector implements ControlValueAccessor {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly trigger = viewChild.required(MatAutocompleteTrigger);
  private readonly campo = viewChild.required<ElementRef<HTMLInputElement>>('campo');
  readonly etiqueta = input('Suplidor');
  readonly requerido = input(false);
  /** Texto cuando no hay suplidor (p. ej. "Todos" en un filtro). */
  readonly vacio = input('');
  /** Después de actualizar el modelo. */
  readonly cambio = output<number | null>();
  protected readonly texto = new FormControl<string | Proveedor>('');
  private actual: Proveedor | null = null;
  private onChange: (v: number | null) => void = () => {};
  private onTouched: () => void = () => {};

  protected readonly opciones = toSignal(this.texto.valueChanges.pipe(
    filter((v) => typeof v === 'string'),
    debounceTime(250),
    map((v) => (v as string).trim()),
    distinctUntilChanged(),
    // Solo sugiere al escribir: con el campo vacío no aparece una lista (para verla toda está la lupa)
    switchMap((q) => !q ? of([] as Proveedor[]) : this.http.get<Pagina<Proveedor>>('/api/v1/catalogos/proveedores', { params: params({ q, size: 15, sort: 'nombre' }) })
      .pipe(map((p) => p.content.filter((x) => x.estado === 'A')), catchError(() => of([])))),
  ), { initialValue: [] as Proveedor[] });

  writeValue(id: number | null): void {
    if (!id) { this.actual = null; this.texto.setValue('', { emitEvent: false }); return; }
    if (this.actual?.id === id) return;
    // El padre fija un suplidor (edición, valor inicial): se busca para mostrar su nombre
    this.http.get<Proveedor>(`/api/v1/catalogos/proveedores/${id}`).subscribe((p) => {
      this.actual = p;
      this.texto.setValue(p, { emitEvent: false });
    });
  }
  registerOnChange(fn: (v: number | null) => void): void { this.onChange = fn; }
  registerOnTouched(fn: () => void): void { this.onTouched = fn; }
  setDisabledState(d: boolean): void { if (d) this.texto.disable(); else this.texto.enable(); }

  protected fijar(p: Proveedor | null): void {
    if ((p?.id ?? null) === (this.actual?.id ?? null)) return;
    this.actual = p;
    this.texto.setValue(p ?? '', { emitEvent: false });
    this.onChange(p?.id ?? null);
    this.cambio.emit(p?.id ?? null);
  }

  /** Al salir del campo: si se borró el texto, queda sin suplidor; si quedó texto sin elegir, se restaura el elegido. */
  protected alSalir(): void {
    this.onTouched();
    const v = this.texto.value;
    if (typeof v !== 'string') return;
    if (!v.trim()) this.fijar(null);
    else if (!this.trigger().panelOpen) this.texto.setValue(this.actual ?? '', { emitEvent: false });
  }

  protected buscar(ev?: Event): void {
    ev?.preventDefault();
    ev?.stopPropagation();   // el clic de la lupa no llega al campo (lo enfocaría y reabriría las sugerencias)
    this.trigger().closePanel();
    const v = this.texto.value;
    this.dialog.open(ProveedorBusquedaDialog, { data: { texto: typeof v === 'string' ? v.trim() : '' }, width: 'min(900px, 96vw)',
      maxWidth: '96vw', restoreFocus: false })
      .afterClosed().subscribe((p?: Proveedor) => {
        if (p) this.fijar(p);
        setTimeout(() => { this.campo().nativeElement.focus(); this.trigger().closePanel(); });
      });
  }

  protected mostrar = (p: Proveedor | string | null) => (typeof p === 'string' ? p : p?.nombre ?? '');
}
