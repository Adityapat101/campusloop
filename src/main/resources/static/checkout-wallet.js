export const STARTING_BALANCE = 10000;
const STORAGE_KEY = "campusloop.checkout.v1";

export function toCents(value) {
  const amount = Number(value);
  const cents = Math.round(amount * 100);
  if (!Number.isFinite(amount) || amount <= 0 || cents <= 0 || !Number.isSafeInteger(cents)
      || Math.abs(amount * 100 - cents) > 0.00001) {
    throw new Error("This item has an invalid price.");
  }
  return cents;
}

export function balance(wallet) {
  return STARTING_BALANCE - wallet.purchases.reduce((sum, purchase) => sum + purchase.cents, 0);
}

export function loadWallet(storage) {
  try {
    const wallet = JSON.parse(storage.getItem(STORAGE_KEY));
    if (!wallet || !Array.isArray(wallet.purchases)) return { purchases: [] };
    const ids = new Set();
    for (const purchase of wallet.purchases) {
      if (!purchase.id || !purchase.listingId || !Number.isSafeInteger(purchase.cents)
          || purchase.cents <= 0 || ids.has(purchase.id)) return { purchases: [] };
      ids.add(purchase.id);
    }
    if (balance(wallet) < 0) return { purchases: [] };
    return wallet;
  } catch {
    return { purchases: [] };
  }
}

export function recordPurchase(wallet, order) {
  if (order.status !== "COMPLETED" || !order.id || !order.listingId) {
    throw new Error("The purchase has not been confirmed.");
  }
  if (wallet.purchases.some(purchase => purchase.id === order.id || purchase.listingId === order.listingId)) {
    return wallet;
  }
  const cents = toCents(order.price);
  if (cents > balance(wallet)) throw new Error("There isn't enough balance for this item.");
  return { purchases: [...wallet.purchases, { ...order, cents }] };
}

export function saveWallet(storage, wallet) {
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify(wallet));
  } catch {
    // Checkout remains usable when browser storage is unavailable.
  }
}
