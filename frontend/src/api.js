// Thin fetch wrapper around the backend REST API.

const TOKEN_KEY = "shop.token";

export const auth = {
  get token() {
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  },
  set token(value) {
    try {
      if (value) localStorage.setItem(TOKEN_KEY, value);
      else localStorage.removeItem(TOKEN_KEY);
    } catch {
      /* storage unavailable: token lives only for this page */
    }
  },
};

async function request(path, { method = "GET", body, headers = {} } = {}) {
  const h = { ...headers };
  if (body !== undefined) h["Content-Type"] = "application/json";
  if (auth.token) h["Authorization"] = `Bearer ${auth.token}`;

  const res = await fetch(path, {
    method,
    headers: h,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (res.status === 204) return null;
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    const message = data?.detail || data?.title || `Request failed (${res.status})`;
    const err = new Error(message);
    err.status = res.status;
    throw err;
  }
  return data;
}

export const api = {
  register: (email, password) => request("/api/auth/register", { method: "POST", body: { email, password } }),
  login: (email, password) => request("/api/auth/login", { method: "POST", body: { email, password } }),

  products: () => request("/api/products?size=50"),

  cart: () => request("/api/cart"),
  setCartQuantity: (productId, quantity) =>
    request("/api/cart/items", { method: "PUT", body: { productId, quantity } }),

  // The same key must be sent again if the user retries after a network error,
  // so the backend returns the original order instead of charging twice.
  checkout: (idempotencyKey) =>
    request("/api/orders/checkout", { method: "POST", headers: { "Idempotency-Key": idempotencyKey } }),
  orders: () => request("/api/orders"),
};
