import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import type { Pagina, S } from '../../api/tipos';
import { Notificacion } from '../../core/notificacion.service';
import { ArticuloBuscador } from '../../shared/articulo-buscador';
import { ConfirmarDialog } from '../../shared/confirmar-dialog';
import { params } from '../../shared/http';

type Doc = S['DocumentoInventarioResponse'];
type Tipo = 'AJUSTE' | 'ENTRADA' | 'SALIDA' | 'TRANSFERENCIA';
interface Linea { articulo: S['ArticuloResponse']; cantidad: number; }

const AYUDA: Record<Tipo, string> = {
  AJUSTE: 'Escriba el CONTEO FÍSICO de cada artículo. Al aplicar, el sistema calcula la diferencia contra la existencia y ajusta.',
  ENTRADA: 'Entrada de mercancía sin factura de compra (donaciones, sobrantes, inventario inicial).',
  SALIDA: 'Salida de mercancía sin venta (consumo interno, mermas, mercancía dañada).',
  TRANSFERENCIA: 'Traslado de mercancía de un almacén a otro.',
};

/** Ajustes por conteo, entradas, salidas y transferencias (legacy ajuste / transentsalida). */
@Component({
  selector: 'app-documentos-inventario',
  imports: [DatePipe, DecimalPipe, FormsModule, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatTableModule, MatPaginatorModule, ArticuloBuscador],
  templateUrl: './documentos.html',
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .celda { width: 110px; padding: 6px; text-align: right; } section { margin-top: 16px; }
    .negativo { color: var(--mat-sys-error); } .positivo { color: var(--mat-sys-primary); }`,
})
export class Documentos {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  protected readonly columnas = ['numero', 'fecha', 'tipo', 'almacen', 'descripcion', 'estado'];
  protected readonly pagina = signal<Pagina<Doc> | null>(null);
  protected readonly almacenes = signal<S['AlmacenResponse'][]>([]);
  protected readonly nombres = signal<Record<number, string>>({});
  protected readonly nuevo = signal(false);
  protected readonly lineas = signal<Linea[]>([]);
  protected readonly seleccionado = signal<Doc | null>(null);
  protected tipo: Tipo = 'AJUSTE';
  protected almacenId: number | null = null;
  protected almacenDestinoId: number | null = null;
  protected descripcion = '';
  protected readonly ayuda = AYUDA;
  protected readonly etiquetaCantidad = computed(() => 'Cantidad');

  constructor() {
    this.http.get<Pagina<S['AlmacenResponse']>>('/api/v1/catalogos/almacenes', { params: params({ size: 100 }) }).subscribe((p) => {
      const activos = p.content.filter((a) => a.estado === 'A');
      this.almacenes.set(activos);
      this.almacenId = activos.find((a) => a.principal)?.id ?? activos[0]?.id ?? null;
    });
    this.cargar();
  }

  protected nombreAlmacen(id?: number | null): string { return this.almacenes().find((a) => a.id === id)?.nombre ?? String(id ?? ''); }

  protected cargar(e?: PageEvent): void {
    this.http.get<Pagina<Doc>>('/api/v1/inventario/documentos', { params: params({ page: e?.pageIndex ?? 0, size: 20 }) })
      .subscribe({ next: (p) => this.pagina.set(p), error: (err) => this.notif.error(err) });
  }

  protected agregar(a: S['ArticuloResponse']): void {
    if (a.tipo !== 'B') { this.notif.error(null, `'${a.nombre}' es un servicio y no maneja inventario`); return; }
    if (this.lineas().some((l) => l.articulo.id === a.id)) { this.notif.error(null, `'${a.nombre}' ya está en el documento`); return; }
    this.nombres.set({ ...this.nombres(), [a.id!]: a.nombre! });
    // En un ajuste se propone la existencia actual como conteo inicial
    this.lineas.set([...this.lineas(), { articulo: a, cantidad: this.tipo === 'AJUSTE' ? (a.existencia ?? 0) : 1 }]);
  }

  protected fijar(i: number, v: number): void {
    this.lineas.set(this.lineas().map((l, j) => (j === i ? { ...l, cantidad: Math.max(0, v || 0) } : l)));
  }

  protected quitar(i: number): void { this.lineas.set(this.lineas().filter((_, j) => j !== i)); }

  protected guardar(aplicar: boolean): void {
    const req: S['DocumentoInventarioRequest'] = {
      tipo: this.tipo, almacenId: this.almacenId ?? undefined, descripcion: this.descripcion || undefined,
      almacenDestinoId: this.tipo === 'TRANSFERENCIA' ? this.almacenDestinoId ?? undefined : undefined,
      lineas: this.lineas().map((l) => ({ articuloId: l.articulo.id!, cantidad: l.cantidad })),
    };
    this.http.post<Doc>('/api/v1/inventario/documentos', req).subscribe({
      next: (d) => {
        this.nuevo.set(false); this.lineas.set([]); this.descripcion = '';
        if (aplicar) this.aplicar(d, false); else { this.notif.ok(`Documento ${d.numero} guardado como borrador`); this.cargar(); this.seleccionado.set(d); }
      },
      error: (e) => this.notif.error(e),
    });
  }

  protected ver(d: Doc): void {
    this.seleccionado.set(this.seleccionado()?.id === d.id ? null : d);
    for (const l of d.lineas ?? []) {
      if (!this.nombres()[l.articuloId!]) this.http.get<S['ArticuloResponse']>(`/api/v1/inventario/articulos/${l.articuloId}`)
        .subscribe((a) => this.nombres.set({ ...this.nombres(), [a.id!]: a.nombre! }));
    }
  }

  protected aplicar(d: Doc, confirmar = true): void {
    const ejecutar = () => this.http.post<Doc>(`/api/v1/inventario/documentos/${d.id}/aplicar`, {}).subscribe({
      next: (r) => { this.notif.ok(`Documento ${r.numero} aplicado al inventario`); this.cargar(); this.ver(r); this.seleccionado.set(r); },
      error: (e) => { this.notif.error(e); this.cargar(); },
    });
    if (!confirmar) { ejecutar(); return; }
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Aplicar documento ${d.numero}`, accion: 'Aplicar',
      mensaje: 'Se moverá el inventario (kardex). La operación no se puede revertir; para corregir se registra otro documento.' } })
      .afterClosed().subscribe((ok) => ok && ejecutar());
  }

  protected anular(d: Doc): void {
    this.http.post(`/api/v1/inventario/documentos/${d.id}/anular`, {}).subscribe({
      next: () => { this.notif.ok('Documento anulado'); this.cargar(); this.seleccionado.set(null); }, error: (e) => this.notif.error(e) });
  }
}
