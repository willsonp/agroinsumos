import { Component } from '@angular/core';
import { CatalogoCrud } from '../../shared/catalogo-crud';
import { PROVEEDORES } from './configs';

@Component({
  selector: 'app-proveedores',
  imports: [CatalogoCrud],
  template: `<div class="pagina"><div class="pagina-titulo"><h1>Proveedores</h1></div>
    <div class="tarjeta"><app-catalogo-crud [config]="config" /></div></div>`,
})
export class Proveedores { protected readonly config = PROVEEDORES; }
