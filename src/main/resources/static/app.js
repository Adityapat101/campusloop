const state = { users: [], category: "", query: "" };

const listingsElement = document.querySelector("#listings");
const countElement = document.querySelector("#result-count");
const noticeElement = document.querySelector("#notice");
const dialog = document.querySelector("#sell-dialog");
const form = document.querySelector("#sell-form");

function escapeHtml(value) {
  const div = document.createElement("div");
  div.textContent = value;
  return div.innerHTML;
}

function showNotice(message, error = false) {
  noticeElement.textContent = message;
  noticeElement.classList.toggle("error", error);
  noticeElement.hidden = false;
  window.setTimeout(() => { noticeElement.hidden = true; }, 4500);
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json", ...options.headers },
    ...options
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.detail || `Request failed (${response.status})`);
  }
  return response.status === 204 ? null : response.json();
}

async function loadUsers() {
  state.users = await api("/api/users");
  document.querySelector("#seller").innerHTML = state.users
    .map(user => `<option value="${user.id}">${escapeHtml(user.name)} — ${escapeHtml(user.campus)}</option>`)
    .join("");
}

async function loadListings() {
  const params = new URLSearchParams();
  if (state.category) params.set("category", state.category);
  if (state.query) params.set("q", state.query);
  listingsElement.innerHTML = '<div class="empty">Gathering the latest finds…</div>';
  try {
    const listings = await api(`/api/listings?${params}`);
    countElement.textContent = `${listings.length} ${listings.length === 1 ? "listing" : "listings"}`;
    listingsElement.innerHTML = listings.length ? listings.map(listingCard).join("")
      : '<div class="empty">Nothing matches yet. Try another search—or be the first to list it.</div>';
  } catch (error) {
    listingsElement.innerHTML = '<div class="empty">The marketplace is taking a quick break.</div>';
    showNotice(error.message, true);
  }
}

function listingCard(listing) {
  return `
    <article class="listing-card">
      <div class="listing-top">
        <span class="tag">${escapeHtml(listing.category)}</span>
        <span class="price">$${Number(listing.price).toFixed(2)}</span>
      </div>
      <h3>${escapeHtml(listing.title)}</h3>
      <p>${escapeHtml(listing.description)}</p>
      <div class="listing-bottom">
        <span class="condition">${listing.condition.replaceAll("_", " ").toLowerCase()}</span>
        <button class="buy" data-listing="${listing.id}">Buy it →</button>
      </div>
    </article>`;
}

document.querySelector("#categories").addEventListener("click", event => {
  const button = event.target.closest("button");
  if (!button) return;
  document.querySelectorAll(".chip").forEach(chip => chip.classList.remove("active"));
  button.classList.add("active");
  state.category = button.dataset.category;
  loadListings();
});

let searchTimer;
document.querySelector("#search").addEventListener("input", event => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => {
    state.query = event.target.value.trim();
    loadListings();
  }, 250);
});

listingsElement.addEventListener("click", async event => {
  const button = event.target.closest(".buy");
  if (!button) return;
  if (state.users.length < 2) return showNotice("Create a second demo user before buying.", true);
  button.disabled = true;
  try {
    await api("/api/orders", {
      method: "POST",
      body: JSON.stringify({ buyerId: state.users[1].id, listingId: button.dataset.listing })
    });
    showNotice("Sold! The demo purchase was recorded in PostgreSQL.");
    await loadListings();
  } catch (error) {
    showNotice(error.message, true);
    button.disabled = false;
  }
});

document.querySelector("#open-sell").addEventListener("click", () => dialog.showModal());
document.querySelector("#close-sell").addEventListener("click", () => dialog.close());

form.addEventListener("submit", async event => {
  event.preventDefault();
  const values = Object.fromEntries(new FormData(form));
  values.price = Number(values.price);
  try {
    await api("/api/listings", { method: "POST", body: JSON.stringify(values) });
    form.reset();
    dialog.close();
    showNotice("Your listing is live.");
    await loadListings();
  } catch (error) {
    showNotice(error.message, true);
  }
});

Promise.all([loadUsers(), loadListings()]).catch(error => showNotice(error.message, true));
