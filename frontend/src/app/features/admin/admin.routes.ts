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
        title: 'New product | DS Catalog',
        loadComponent: () =>
          import('./admin-product-form/admin-product-form').then(
            (module) => module.AdminProductForm,
          ),
      },
      {
        path: 'products/:id/edit',
        title: 'Edit product | DS Catalog',
        loadComponent: () =>
          import('./admin-product-form/admin-product-form').then(
            (module) => module.AdminProductForm,
          ),
      },
      {
        path: 'products',
        title: 'Products | DS Catalog',
        loadComponent: () =>
          import('./admin-products/admin-products').then((module) => module.AdminProducts),
      },
    ],
  },
];
