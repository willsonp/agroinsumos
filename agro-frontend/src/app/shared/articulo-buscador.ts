import { ESCANEADOS_EMPAQUE } from './calculo';
import { Component, ElementRef, inject, output, viewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatAutocompleteModule, MatAutocompleteTrigger } from '@angular/material/autocomplete';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { catchError, debounceTime, distinctUntilChanged, filter, map, of, switchMap } from 'rxjs';
import type { Pagina, S } from '../api/tipos';
import { params } from './http';
import { Notificacion } from '../core/notificacion.service';

type Articulo = S['ArticuloResponse'];

/**
 * Buscador de artículos. Con lector de código de barras: el lector "escribe" el código y envía Enter, que busca por
 * código exacto (de cualquier largo: "1", "7461234000014"). Escribiendo letras, autocompleta por nombre/referencia;
 * con una opción resaltada (flechas), Enter elige esa opción.
 */
@Component({
  selector: 'app-articulo-buscador',
  imports: [ReactiveFormsModule, MatAutocompleteModule, MatFormFieldModule, MatInputModule, CurrencyPipe, DecimalPipe],
  template: `
    <mat-form-field style="width:100%">
      <mat-label>Artículo</mat-label>
      <span matTextPrefix class="material-symbols-outlined" style="margin-right:6px">barcode_scanner</span>
      <input #campo matInput [formControl]="texto" [matAutocomplete]="auto" (keydown.enter)="porCodigo($event)"
             placeholder="Escanee el código o escriba el nombre" />
      <mat-autocomplete #auto (optionSelected)="elegir($event.option.value)">
        @for (a of opciones(); track a.id) {
          <mat-option [value]="a">
            <div style="display:flex;gap:12px;justify-content:space-between;width:100%">
              <span>{{ a.nombre }} <span class="muted">{{ a.codigoBarra }}</span></span>
              <span class="muted">{{ a.precioContadoConItbis | currency }} · Exist. {{ a.existencia | number: '1.0-2' }}</span>
            </div>
          </mat-option>
        }
      </mat-autocomplete>
    </mat-form-field>`,
})
export class ArticuloBuscador {
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  readonly elegido = output<Articulo>();
  private readonly campo = viewChild.required<ElementRef<HTMLInputElement>>('campo');
  private readonly trigger = viewChild.required(MatAutocompleteTrigger);
  protected readonly texto = new FormControl<string | Articulo>('');

  protected readonly opciones = toSignal(this.texto.valueChanges.pipe(
    filter((v) => typeof v === 'string'),
    map((v) => (v as string).trim()),
    debounceTime(250),
    distinctUntilChanged(),
    switchMap((q) => (q.length < 2 || /^\d{6,}$/.test(q)) ? of([]) :
      this.http.get<Pagina<Articulo>>('/api/v1/inventario/articulos', { params: params({ q, size: 15, sort: 'nombre' }) })
        .pipe(map((p) => p.content.filter((a) => a.estado === 'A')), catchError(() => of([])))),
  ), { initialValue: [] as Articulo[] });

  protected porCodigo(ev: Event): void {
    const v = this.texto.value;
    // Opción resaltada con las flechas: la elige el autocompletado
    if (typeof v !== 'string' || (this.trigger().panelOpen && this.trigger().activeOption)) return;
    const codigo = v.trim();
    if (!codigo) return;
    if (/\s/.test(codigo)) {   // un nombre con espacios no es un código: si coincide con un solo artículo, ese
      if (this.opciones().length === 1) { ev.preventDefault(); this.elegir(this.opciones()[0]); }
      return;
    }
    ev.preventDefault();
    const sugeridas = this.opciones();
    this.http.get<Articulo>(`/api/v1/inventario/articulos/codigo/${encodeURIComponent(codigo)}`).subscribe({
      next: (a) => {
        // Código de la caja/saco: la línea entra como presentación
        if (a.codigoBarraEmpaque && a.codigoBarraEmpaque === codigo) ESCANEADOS_EMPAQUE.add(a);
        this.trigger().closePanel();
        this.elegir(a);
      },
      // No es un código: si lo escrito coincide con un solo artículo, se agrega ese
      error: () => sugeridas.length === 1 ? this.elegir(sugeridas[0])
        : this.notif.error(null, sugeridas.length ? `Elija el artículo de la lista o escanee su código` : `No existe un artículo con el código ${codigo}`),
    });
  }

  protected elegir(a: Articulo): void {
    this.elegido.emit(a);
    this.texto.setValue('');
    this.enfocar();
  }

  enfocar(): void {
    setTimeout(() => this.campo().nativeElement.focus());
  }
}
