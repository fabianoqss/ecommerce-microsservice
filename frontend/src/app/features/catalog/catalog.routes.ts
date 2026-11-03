import { Routes } from '@angular/router';

export const catalogRoutes: Routes = [
  {
    path: '',
    loadComponent: () => import('./product-list/product-list').then((module) => module.ProductList),
  },
];
