import { useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";

import { useCart } from "../../cart";
import { createPaymentSession, submitCheckout } from "../api/checkout-client";
import { toOrder } from "../api/order.mappers";
import type { CartSummary, Order } from "../domain/order";
import { CheckoutForm } from "./checkout-form";

type CheckoutPageProps = Readonly<{
  redirectToPayment?: (url: string) => void;
}>;

function toSummary(
  cartId: number,
  currency: string | null,
  items: ReadonlyArray<{
    productId: number;
    productName: string;
    price: number;
    quantity: number;
  }>,
): CartSummary {
  const lines = items.map((item) => ({
    lineTotal: Number((item.price * item.quantity).toFixed(2)),
    productId: item.productId,
    productName: item.productName,
    quantity: item.quantity,
    unitPrice: item.price,
  }));
  const total = Number(
    lines.reduce((sum, line) => sum + line.lineTotal, 0).toFixed(2),
  );
  return { currency, id: cartId, items: lines, total };
}

export function CheckoutPage({
  redirectToPayment = (url) => window.location.assign(url),
}: CheckoutPageProps = {}) {
  const { cart, clear, loading, error } = useCart();
  const [searchParams] = useSearchParams();
  const [paymentRetryError, setPaymentRetryError] = useState<string>();
  const [retryingPayment, setRetryingPayment] = useState(false);
  const cancelledOrderId = Number(searchParams.get("orderId"));
  const paymentCancelled =
    searchParams.get("payment") === "cancelled" &&
    Number.isSafeInteger(cancelledOrderId) &&
    cancelledOrderId > 0;

  const summary = useMemo(
    () => (cart ? toSummary(cart.id, cart.currency, cart.items) : null),
    [cart],
  );

  async function handleConfirmed(order: Order): Promise<void> {
    if (!order.paymentUrl) {
      throw new Error("Payment session URL was not returned.");
    }
    clear();
    redirectToPayment(order.paymentUrl);
  }

  async function retryPayment(): Promise<void> {
    setRetryingPayment(true);
    setPaymentRetryError(undefined);
    try {
      const payment = await createPaymentSession(cancelledOrderId);
      redirectToPayment(payment.checkoutUrl);
    } catch {
      setPaymentRetryError("Unable to restart payment. Please try again.");
    } finally {
      setRetryingPayment(false);
    }
  }

  if (loading) {
    return (
      <main>
        <div role="status">Loading checkout...</div>
      </main>
    );
  }

  if (paymentCancelled) {
    return (
      <main>
        <h1>Payment not completed</h1>
        <p>Your order is still reserved. You can return to secure checkout.</p>
        {paymentRetryError && <p role="alert">{paymentRetryError}</p>}
        <button
          type="button"
          disabled={retryingPayment}
          onClick={() => void retryPayment()}
        >
          {retryingPayment ? "Starting payment..." : "Pay for order"}
        </button>
        <Link to="/products">Continue shopping</Link>
      </main>
    );
  }

  if (error) {
    return (
      <main>
        <h1>Checkout</h1>
        <div role="alert">{error}</div>
        <Link to="/cart">Back to cart</Link>
      </main>
    );
  }

  if (!summary || summary.items.length === 0) {
    return (
      <main>
        <h1>Checkout</h1>
        <p>Your cart is empty.</p>
        <Link to="/products">Browse products</Link>
      </main>
    );
  }

  return (
    <main>
      <div className="steps" aria-label="Checkout progress">
        <span>01 Cart</span>
        <span className="current">02 Review &amp; place order</span>
        <span>03 Confirmation</span>
      </div>
      <CheckoutForm
        cart={summary}
        submitOrder={async (input) => {
          const order = toOrder(await submitCheckout(input));
          const payment = await createPaymentSession(order.id);
          return { ...order, paymentUrl: payment.checkoutUrl };
        }}
        onConfirmed={handleConfirmed}
      />
      <div style={{ marginTop: "16px" }}>
        <Link to="/cart">Back to cart</Link>
      </div>
    </main>
  );
}
