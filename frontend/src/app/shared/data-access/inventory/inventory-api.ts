import { computed, inject, Service } from '@angular/core';
import { HttpClient, HttpParams, httpResource } from '@angular/common/http';
import { InventoryItem, InventoryRequest, InventoryUpdate } from './inventory';

const sameCodes = (a: readonly string[], b: readonly string[]) =>
  a.length === b.length && a.every((code, index) => code === b[index]);

@Service()
export class InventoryApi {
  private readonly http = inject(HttpClient);

  create(body: InventoryRequest) {
    return this.http.post<InventoryItem>('/api/inventory', body);
  }

  update(skuCode: string, body: InventoryUpdate) {
    return this.http.put<InventoryItem>('/api/inventory/' + encodeURIComponent(skuCode), body);
  }

  getBySkus(skus: string[]) {
    const params = new HttpParams({ fromObject: { skuCode: skus } });
    return this.http.get<InventoryItem[]>('/api/inventory', { params });
  }

  bySkus(skus: () => string[]) {
    const codes = computed(() => skus(), { equal: sameCodes });
    return httpResource<InventoryItem[]>(() => {
      const current = codes();
      if (current.length === 0) return undefined;
      const params = new HttpParams({ fromObject: { skuCode: current } });
      return { url: '/api/inventory', params };
    });
  }
}
