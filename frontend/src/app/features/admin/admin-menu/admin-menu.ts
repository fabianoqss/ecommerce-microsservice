import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

@Component({
  selector: 'app-admin-menu',
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './admin-menu.html',
})
export class AdminMenu {
  protected readonly unavailableItems = ['Categorias', 'Usuários'];
}
