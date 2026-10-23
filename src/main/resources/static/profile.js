const STORAGE_KEY = "campusloop.profile.v1";
let memoryProfile = null;

function isProfile(value) {
  return value && typeof value.id === "string" && value.id.trim()
    && typeof value.name === "string" && value.name.trim()
    && typeof value.campus === "string" && value.campus.trim();
}

export function getProfile() {
  try {
    const stored = JSON.parse(window.sessionStorage.getItem(STORAGE_KEY));
    if (isProfile(stored)) {
      memoryProfile = stored;
      return stored;
    }
  } catch {
    // Keep the current page usable when browser storage is unavailable.
  }
  return memoryProfile;
}

export async function ensureProfile(name, campus) {
  const cleanName = typeof name === "string" ? name.trim() : "";
  const cleanCampus = typeof campus === "string" ? campus.trim() : "";
  if (!cleanName || cleanName.length > 100) {
    throw new Error("Enter your name using 1 to 100 characters.");
  }
  if (!cleanCampus || cleanCampus.length > 100) {
    throw new Error("Enter your campus using 1 to 100 characters.");
  }

  const saved = getProfile();
  if (saved && saved.name === cleanName && saved.campus === cleanCampus) return saved;

  const response = await fetch("/api/users", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      name: cleanName,
      campus: cleanCampus,
      email: `profile-${crypto.randomUUID()}@campusloop.invalid`,
    }),
  });
  const profile = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(profile.detail || `We couldn't save your profile (${response.status}). Please try again.`);
  }
  if (!isProfile(profile)) throw new Error("We couldn't load your saved profile. Please try again.");

  memoryProfile = profile;
  try {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(profile));
  } catch {
    // The profile remains available for this page when storage is unavailable.
  }
  return profile;
}
