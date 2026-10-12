export type CartStatus = "OPEN" | "CHECKED_OUT";

export type CartItem = Readonly<{
  id: number;
  price: number;
  productId: number;
  productName: string;
  quantity: number;
}>;

export type Cart = Readonly<{
  currency: string | null;
  id: number;
  items: readonly CartItem[];
  status: CartStatus;
}>;
