import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ReceiptPanel } from "../../ledger";
import { fetchOrder, OrderClientError } from "../api/order-client";
import { toOrder } from "../api/order.mappers";
import type { Order } from "../domain/order";

export function ConfirmationPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState<OrderClientError | Error | null>(null);
  const [retry, setRetry] = useState(0);
  const id = Number(orderId);
  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let polls = 0;
    setOrder(null);
    setError(null);
    if (!Number.isSafeInteger(id) || id <= 0) {
      setError(new Error("Invalid order."));
      return;
    }
    const loadOrder = async (): Promise<void> => {
      try {
        const value = await fetchOrder(id);
        if (!active) return;
        const updatedOrder = toOrder(value);
        setOrder(updatedOrder);
        if (updatedOrder.status === "PENDING" && polls < 15) {
          polls += 1;
          timer = setTimeout(() => void loadOrder(), 2000);
        }
      } catch (cause) {
        if (active) {
          setError(
            cause instanceof Error ? cause : new Error("Unable to load order"),
          );
        }
      }
    };
    void loadOrder();
    return () => {
      active = false;
      if (timer) clearTimeout(timer);
    };
  }, [id, retry]);
  if (error)
    return (
      <main>
        <div className="state-panel">
          <h1>Order details</h1>
          <p role="alert">{error.message}</p>
          {error instanceof OrderClientError && error.status === 401 ? (
            <Link
              className="button"
              to="/login"
              state={{ from: `/confirmation/${id}` }}
            >
              Sign in again
            </Link>
          ) : (
            <Link to="/orders">View all orders</Link>
          )}
          {!(error instanceof OrderClientError) || error.status >= 500 ? (
            <p>
              <button onClick={() => setRetry((value) => value + 1)}>
                Try again
              </button>
            </p>
          ) : null}
        </div>
      </main>
    );
  if (!order)
    return (
      <main>
        <div className="state-panel">
          <p role="status">Loading order details...</p>
        </div>
      </main>
    );
  return (
    <main>
      <header className="confirmation-header">
        {order.status === "COMPLETED" && (
          <span className="success-mark" aria-hidden="true">
            ✓
          </span>
        )}
        <p className="eyebrow">
          {order.status === "COMPLETED" ? "PAYMENT RECEIVED" : "ORDER STATUS"}
        </p>
        <h1>
          {order.status === "COMPLETED"
            ? "Order Confirmed"
            : "Payment processing"}
        </h1>
        <p role="status">
          {order.status === "PENDING"
            ? "We are confirming your payment. This page updates automatically."
            : order.status === "CANCELLED"
              ? "Payment was not completed and the order was cancelled."
              : "Thank you for your order!"}
        </p>
        <p>Order ID: {order.id}</p>
      </header>
      <section className="order-details" aria-label="Order details">
        <div className="order-meta">
          <time dateTime={order.orderDate}>
            {new Date(order.orderDate).toLocaleString()}
          </time>
          <span className="badge">Status: {order.status}</span>
        </div>
        <ul className="order-lines">
          {order.orderLines.map((line) => (
            <li key={line.productId}>
              {line.productName} × {line.quantity} — {line.lineTotal.toFixed(2)}
            </li>
          ))}
        </ul>
        <p className="order-total">
          Total: {order.total.toFixed(2)} {order.currency ?? "SEK"}
        </p>
        {order.status === "COMPLETED" && <ReceiptPanel orderId={order.id} />}
      </section>
      <div className="confirmation-actions">
        <Link className="button" to="/products">
          Continue shopping
        </Link>
        <Link className="button button-secondary" to="/orders">
          View all orders
        </Link>
      </div>
    </main>
  );
}
