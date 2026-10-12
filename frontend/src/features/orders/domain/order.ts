export type OrderStatus = "PENDING" | "COMPLETED" | "CANCELLED";

export type OrderLine = Readonly<{
  lineTotal: number;
  productId: number;
  productName: string;
  quantity: number;
  unitPrice: number;
}>;

export type Order = Readonly<{
  cartId: number;
  currency?: string;
  id: number;
  orderDate: string;
  orderLines: readonly OrderLine[];
  paymentUrl?: string;
  status: OrderStatus;
  total: number;
}>;

export type CartSummary = Readonly<{
  currency?: string | null;
  id: number;
  items: readonly OrderLine[];
  total: number;
}>;
