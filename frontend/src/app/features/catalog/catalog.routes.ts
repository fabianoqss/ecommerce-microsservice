import { Routes } from '@angular/router';

export const catalogRoutes: Routes = [
  {
    path: '',
    loadComponent: () => import('./product-list/product-list').then((module) => module.ProductList),
  },
  {
    path: ':id',
    title: 'Product | DS Catalog',
    loadComponent: () =>
      import('./product-detail/product-detail').then((module) => module.ProductDetail),
  },
];
