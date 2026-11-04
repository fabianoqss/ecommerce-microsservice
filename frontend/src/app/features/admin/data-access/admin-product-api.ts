import { HttpClient } from '@angular/common/http';
import { inject, Service } from '@angular/core';
import { Product } from '../../../shared/data-access/product/product';

export interface AdminProductRequest {
  name: string;
  description: string;
  price: number;
  category: string;
  imageUrl: string | null;
}

@Service()
export class AdminProductApi {
  private readonly http = inject(HttpClient);

  create(body: AdminProductRequest) {
    return this.http.post<Product>('/api/product', body);
  }

  update(id: string, version: number, body: AdminProductRequest) {
    return this.http.put<Product>('/api/product/' + encodeURIComponent(id), body, {
      headers: { 'If-Match': `"${version}"` },
    });
  }

  remove(id: string, version: number) {
    return this.http.delete<void>('/api/product/' + encodeURIComponent(id), {
      headers: { 'If-Match': `"${version}"` },
    });
  }
}
