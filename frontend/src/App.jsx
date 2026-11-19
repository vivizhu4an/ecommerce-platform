import { useCallback, useEffect, useRef, useState } from "react";
import { api, auth } from "./api.js";

const money = (n) => `$${Number(n).toFixed(2)}`;

function AuthForm({ onAuthenticated }) {
  const [mode, setMode] = useState("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState(null);

  async function submit(e) {
    e.preventDefault();
    setError(null);
    try {
      const res = mode === "login" ? await api.login(email, password) : await api.register(email, password);
      auth.token = res.accessToken;
      onAuthenticated();
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <form className="card auth" onSubmit={submit}>
      <h2>{mode === "login" ? "Sign in" : "Create account"}</h2>
      <input type="email" placeholder="Email" value={email} onChange={(e) => setEmail(e.target.value)} required />
      <input
        type="password"
        placeholder="Password (8+ characters)"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        minLength={8}
        required
      />
      {error && <p className="error">{error}</p>}
      <button type="submit">{mode === "login" ? "Sign in" : "Register"}</button>
      <button type="button" className="link" onClick={() => setMode(mode === "login" ? "register" : "login")}>
        {mode === "login" ? "Need an account? Register" : "Have an account? Sign in"}
      </button>
    </form>
  );
}

function ProductGrid({ products, onAdd, cartQty }) {
  return (
    <section className="grid">
      {products.map((p) => (
        <article key={p.id} className="card product">
          <h3>{p.name}</h3>
          <p className="muted">{p.description}</p>
          <p className="price">{money(p.price)}</p>
          <p className="muted small">{p.stock > 0 ? `${p.stock} in stock` : "Out of stock"}</p>
          <button disabled={p.stock === 0} onClick={() => onAdd(p.id, (cartQty[p.id] || 0) + 1)}>
            Add to cart
          </button>
        </article>
      ))}
    </section>
  );
}

function Cart({ cart, onSetQty, onCheckout, busy }) {
  if (!cart) return null;
  return (
    <aside className="card cart">
      <h2>Cart</h2>
      {cart.items.length === 0 && <p className="muted">Your cart is empty.</p>}
      {cart.items.map((line) => (
        <div key={line.product.id} className="line">
          <span>{line.product.name}</span>
          <span className="qty">
            <button onClick={() => onSetQty(line.product.id, line.quantity - 1)}>−</button>
            {line.quantity}
            <button onClick={() => onSetQty(line.product.id, line.quantity + 1)}>+</button>
          </span>
          <span>{money(line.lineTotal)}</span>
        </div>
      ))}
      <div className="line total">
        <span>Total</span>
        <span>{money(cart.total)}</span>
      </div>
      <button disabled={busy || cart.items.length === 0} onClick={onCheckout}>
        {busy ? "Placing order…" : "Checkout"}
      </button>
    </aside>
  );
}

function Orders({ orders }) {
  if (!orders.length) return null;
  return (
    <section className="card">
      <h2>Your orders</h2>
      <table>
        <thead>
          <tr><th>#</th><th>Status</th><th>Items</th><th>Total</th></tr>
        </thead>
        <tbody>
          {orders.map((o) => (
            <tr key={o.id}>
              <td>{o.id}</td>
              <td><span className={`badge ${o.status.toLowerCase()}`}>{o.status}</span></td>
              <td>{o.items.reduce((n, i) => n + i.quantity, 0)}</td>
              <td>{money(o.total)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

export default function App() {
  const [signedIn, setSignedIn] = useState(Boolean(auth.token));
  const [products, setProducts] = useState([]);
  const [cart, setCart] = useState(null);
  const [orders, setOrders] = useState([]);
  const [message, setMessage] = useState(null);
  const [busy, setBusy] = useState(false);
  // One key per checkout attempt; kept across retries, replaced after success.
  const checkoutKey = useRef(crypto.randomUUID());

  const refresh = useCallback(async () => {
    try {
      const page = await api.products();
      setProducts(page.content);
      if (auth.token) {
        const [c, o] = await Promise.all([api.cart(), api.orders()]);
        setCart(c);
        setOrders(o);
      }
    } catch (err) {
      if (err.status === 401) {
        auth.token = null;
        setSignedIn(false);
      } else {
        setMessage(err.message);
      }
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh, signedIn]);

  // Orders start PENDING and become CONFIRMED once the RabbitMQ consumer runs.
  useEffect(() => {
    if (!orders.some((o) => o.status === "PENDING")) return;
    const t = setTimeout(() => api.orders().then(setOrders).catch(() => {}), 1500);
    return () => clearTimeout(t);
  }, [orders]);

  async function setQty(productId, quantity) {
    try {
      setCart(await api.setCartQuantity(productId, Math.max(0, quantity)));
    } catch (err) {
      setMessage(err.message);
    }
  }

  async function checkout() {
    setBusy(true);
    setMessage(null);
    try {
      const order = await api.checkout(checkoutKey.current);
      checkoutKey.current = crypto.randomUUID();
      setMessage(`Order #${order.id} placed.`);
      await refresh();
    } catch (err) {
      setMessage(err.message);
    } finally {
      setBusy(false);
    }
  }

  const cartQty = Object.fromEntries((cart?.items || []).map((l) => [l.product.id, l.quantity]));

  return (
    <div className="page">
      <header>
        <h1>Trailhead Shop</h1>
        {signedIn && (
          <button
            className="link"
            onClick={() => {
              auth.token = null;
              setSignedIn(false);
              setCart(null);
              setOrders([]);
            }}
          >
            Sign out
          </button>
        )}
      </header>

      {message && <p className="notice">{message}</p>}

      {!signedIn ? (
        <div className="layout">
          <ProductGrid products={products} cartQty={{}} onAdd={() => setMessage("Sign in to add items.")} />
          <AuthForm onAuthenticated={() => setSignedIn(true)} />
        </div>
      ) : (
        <>
          <div className="layout">
            <ProductGrid products={products} cartQty={cartQty} onAdd={setQty} />
            <Cart cart={cart} onSetQty={setQty} onCheckout={checkout} busy={busy} />
          </div>
          <Orders orders={orders} />
        </>
      )}
    </div>
  );
}
