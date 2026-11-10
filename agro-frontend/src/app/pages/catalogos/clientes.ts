import { Component } from '@angular/core';
import { CatalogoCrud } from '../../shared/catalogo-crud';
import { CLIENTES } from './configs';

@Component({
  selector: 'app-clientes',
  imports: [CatalogoCrud],
  template: `<div class="pagina"><div class="pagina-titulo"><h1>Clientes</h1></div>
    <div class="tarjeta"><app-catalogo-crud [config]="config" /></div></div>`,
})
export class Clientes { protected readonly config = CLIENTES; }
