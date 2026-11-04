import { Routes } from '@angular/router';
import { AdminShell } from './admin-shell/admin-shell';

export const adminRoutes: Routes = [
  {
    path: '',
    component: AdminShell,
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'products' },
      {
        path: 'products',
        title: 'Produtos | DS Catalog',
        loadComponent: () =>
          import('./admin-products/admin-products').then((module) => module.AdminProducts),
      },
    ],
  },
];
