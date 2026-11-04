import { Routes } from '@angular/router';
import { AdminShell } from './admin-shell/admin-shell';

export const adminRoutes: Routes = [
  {
    path: '',
    component: AdminShell,
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'products' },
      {
        path: 'products/new',
        title: 'Novo produto | DS Catalog',
        loadComponent: () =>
          import('./admin-product-form/admin-product-form').then(
            (module) => module.AdminProductForm,
          ),
      },
      {
        path: 'products/:id/edit',
        title: 'Editar produto | DS Catalog',
        loadComponent: () =>
          import('./admin-product-form/admin-product-form').then(
            (module) => module.AdminProductForm,
          ),
      },
      {
        path: 'products',
        title: 'Produtos | DS Catalog',
        loadComponent: () =>
          import('./admin-products/admin-products').then((module) => module.AdminProducts),
      },
    ],
  },
];
