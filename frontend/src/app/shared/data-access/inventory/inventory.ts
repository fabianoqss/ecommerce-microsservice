export interface InventoryItem {
  skuCode: string;
  quantity: number;
  inStock: boolean;
}

export interface InventoryUpdate {
  quantity: number;
}

export interface InventoryRequest extends InventoryUpdate {
  skuCode: string;
}
