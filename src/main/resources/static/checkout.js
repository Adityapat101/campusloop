import { balance, loadWallet, recordPurchase, saveWallet, toCents } from "/checkout-wallet.js";
import { getProfile, ensureProfile } from "/profile.js";
import { populateUniversitySelect } from "/universities.js";

const element = id => document.getElementById(id);
const money = cents => new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(cents / 100);
let storage;
try { storage = window.sessionStorage; } catch { storage = null; }
let wallet = loadWallet(storage);
let listing;
let buyer;
let pending = false;
let completed = false;

async function api(path, options = {}) {
  const response = await fetch(path, { ...options, headers: { "Content-Type": "application/json" } });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(body.detail || `We couldn't complete this request (${response.status}). Please try again.`);
    error.status = response.status;
    throw error;
  }
  return body;
}

function showError(message, terminal = false) {
  element("checkout-error").textContent = message;
  element("checkout-error").hidden = false;
  if (terminal) {
    element("checkout-loading").hidden = true;
    element("checkout-review").hidden = true;
    element("checkout-recovery").hidden = false;
  }
}

function renderBalance() {
  const available = balance(wallet);
  const cost = toCents(listing.price);
  const unavailable = listing.status !== "AVAILABLE";
  element("wallet-balance").textContent = money(available);
  element("item-price").textContent = money(cost);
  element("order-total").textContent = money(cost);
  element("balance-after").textContent = cost <= available ? money(available - cost) : "Insufficient balance";
  element("confirmation-question").textContent = unavailable
    ? "This item is no longer available. Choose another find from the marketplace."
    : cost <= available
    ? `Would you like to pay ${money(cost)} for this item?`
    : `You need ${money(cost - available)} more to buy this item.`;
  element("confirm-payment").textContent = unavailable ? "Item unavailable"
    : cost <= available ? `Yes, pay ${money(cost)} →` : "Insufficient balance";
  element("confirm-payment").disabled = pending || completed || unavailable || cost > available;
}

function showReceipt(order) {
  wallet = recordPurchase(wallet, order);
  delete wallet.pending;
  saveWallet(storage, wallet);
  completed = true;
  element("checkout-loading").hidden = true;
  element("checkout-intro").hidden = true;
  element("checkout-review").hidden = true;
  element("checkout-error").hidden = true;
  element("success-description").textContent = `${listing.title} is off the market and on your list of good finds.`;
  element("receipt-amount").textContent = money(toCents(order.price));
  element("receipt-balance").textContent = money(balance(wallet));
  element("receipt-id").textContent = order.id;
  element("checkout-success").hidden = false;
  element("success-heading").focus();
}

element("payment-form").addEventListener("submit", async event => {
  event.preventDefault();
  if (pending || completed || !listing || toCents(listing.price) > balance(wallet)) return;
  pending = true;
  element("checkout-error").hidden = true;
  element("confirm-payment").disabled = true;
  element("confirm-payment").textContent = "Confirming your purchase…";
  try {
    buyer = await ensureProfile(element("checkout-name").value, element("checkout-campus").value);
    if (buyer.id === listing.sellerId) throw new Error("You can't buy your own listing. Choose another item from the marketplace.");
    element("buyer-name").textContent = buyer.name;
    const current = await api(`/api/listings/${encodeURIComponent(listing.id)}`);
    if (current.status !== "AVAILABLE") {
      listing = current;
      throw new Error("This item has already been sold. Choose another find from the marketplace.");
    }
    if (toCents(current.price) !== toCents(listing.price)) {
      listing = current;
      throw new Error("The price has changed. Review the updated total before confirming.");
    }
    wallet = { ...wallet, pending: { listingId: listing.id, buyerId: buyer.id } };
    saveWallet(storage, wallet);
    const order = await api("/api/orders", {
      method: "POST", body: JSON.stringify({ buyerId: buyer.id, listingId: listing.id })
    });
    showReceipt(order);
  } catch (error) {
    const hasIntent = wallet.pending?.listingId === listing.id && wallet.pending?.buyerId === buyer?.id;
    if (!hasIntent || (error.status >= 400 && error.status < 500)) {
      if (hasIntent) {
        delete wallet.pending;
        saveWallet(storage, wallet);
      }
      if (error.status === 404 || error.status === 409) listing.status = "UNAVAILABLE";
      showError(error.message);
      return;
    }
    // A response can be lost after an order is saved. Check before offering a retry.
    try {
      const orders = await api(`/api/orders?buyerId=${encodeURIComponent(buyer.id)}`);
      const saved = orders.find(order => order.listingId === listing.id && order.status === "COMPLETED");
      if (saved) showReceipt(saved);
      else {
        delete wallet.pending;
        saveWallet(storage, wallet);
        showError(error.message);
      }
    } catch {
      showError("We couldn't confirm the purchase status. Reload this page before trying again; your balance has not changed.");
      element("confirm-payment").hidden = true;
      element("checkout-recovery").hidden = false;
    }
  } finally {
    pending = false;
    if (!completed) renderBalance();
  }
});

async function loadCheckout() {
  const listingId = new URLSearchParams(window.location.search).get("listing");
  if (!listingId) return showError("Choose an item from the marketplace to start checkout.", true);
  try {
    const [item, users] = await Promise.all([
      api(`/api/listings/${encodeURIComponent(listingId)}`), api("/api/users"),
      populateUniversitySelect(element("checkout-campus"), getProfile()?.campus || "")
    ]);
    listing = item;
    toCents(listing.price);
    const prior = wallet.purchases.find(purchase => purchase.listingId === listing.id);
    const recovery = prior || (wallet.pending?.listingId === listing.id ? wallet.pending : null);
    if (recovery) {
      const orders = await api(`/api/orders?buyerId=${encodeURIComponent(recovery.buyerId)}`);
      const order = orders.find(order => order.status === "COMPLETED"
        && (prior ? order.id === prior.id : order.listingId === listing.id));
      if (order) return showReceipt(order);
      delete wallet.pending;
      saveWallet(storage, wallet);
    }
    if (listing.status !== "AVAILABLE") throw new Error("This item has already been sold. There's another good find waiting in the marketplace.");
    buyer = getProfile();
    const seller = users.find(user => user.id === listing.sellerId);
    element("item-title").textContent = listing.title;
    element("item-description").textContent = listing.description;
    element("item-category").textContent = listing.category;
    element("item-condition").textContent = listing.condition.replaceAll("_", " ").toLowerCase();
    element("item-seller").textContent = seller?.name || "CampusLoop seller";
    element("checkout-name").value = buyer?.name || "";
    element("checkout-campus").value = buyer?.campus || "";
    element("buyer-name").textContent = buyer?.name || "you";
    renderBalance();
    element("checkout-loading").hidden = true;
    element("checkout-review").hidden = false;
  } catch (error) {
    showError(error.message, true);
  }
}

element("checkout-name").addEventListener("input", event => {
  element("buyer-name").textContent = event.target.value.trim() || "you";
});

loadCheckout();
