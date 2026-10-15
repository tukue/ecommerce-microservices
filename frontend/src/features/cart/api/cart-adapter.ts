const API_BASE = "/api/customer";

export interface CartItemDTO {
  id: number;
  productId: number;
  productName: string;
  price: number;
  quantity: number;
}

export interface CartDTO {
  currency?: string | null;
  id: number;
  status: string;
  items: CartItemDTO[];
}

export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path?: string;
  validationErrors?: Record<string, string>;
}

export class CartAdapter {
  private token: string;

  constructor(token = "") {
    this.token = token;
  }

  private async request<T>(
    path: string,
    options: RequestInit = {},
  ): Promise<T> {
    const response = await fetch(`${API_BASE}${path}`, {
      ...options,
      headers: {
        "Content-Type": "application/json",
        ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}),
        ...options.headers,
      },
    });

    if (!response.ok) {
      const body = await response.json().catch(() => null);
      const error: ApiError = body ?? {
        timestamp: new Date().toISOString(),
        status: response.status,
        error: response.statusText,
        message: `Request failed with status ${response.status}`,
      };
      throw error;
    }

    if (response.status === 204) return undefined as T;
    return response.json();
  }

  async getCurrentCart(): Promise<CartDTO | null> {
    try {
      return await this.request<CartDTO>("/cart");
    } catch (err) {
      if ((err as ApiError).status === 404) return null;
      throw err;
    }
  }

  async createCart(): Promise<CartDTO> {
    return this.request<CartDTO>("/cart", { method: "POST" });
  }

  async addItem(
    cartId: number,
    productId: number,
    quantity: number,
  ): Promise<CartDTO> {
    return this.request<CartDTO>(`/cart/${cartId}/items`, {
      method: "POST",
      body: JSON.stringify({ productId, quantity }),
    });
  }

  async updateItemQuantity(
    cartId: number,
    itemId: number,
    quantity: number,
  ): Promise<CartDTO> {
    return this.request<CartDTO>(`/cart/${cartId}/items/${itemId}`, {
      method: "PATCH",
      body: JSON.stringify({ quantity }),
    });
  }

  async removeItem(cartId: number, itemId: number): Promise<CartDTO> {
    return this.request<CartDTO>(`/cart/${cartId}/items/${itemId}`, {
      method: "DELETE",
    });
  }
}
