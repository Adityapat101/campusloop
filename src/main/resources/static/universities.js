let universityNames;

async function loadUniversityNames() {
  if (!universityNames) {
    universityNames = fetch("/universities.json")
      .then(async response => {
        if (!response.ok) throw new Error("University list could not be loaded.");
        const names = await response.json();
        if (!Array.isArray(names) || !names.length
            || names.some(name => typeof name !== "string" || !name.trim())) {
          throw new Error("University list could not be loaded.");
        }
        return names;
      })
      .catch(error => {
        universityNames = null;
        throw error;
      });
  }
  return universityNames;
}

function promptOption(message) {
  const option = document.createElement("option");
  option.value = "";
  option.textContent = message;
  option.disabled = true;
  option.selected = true;
  return option;
}

/** Populate a required native select with the bundled U.S. university names. */
export async function populateUniversitySelect(select, selected = "") {
  select.disabled = true;
  select.replaceChildren(promptOption("Loading universities…"));
  try {
    const names = await loadUniversityNames();
    const options = document.createDocumentFragment();
    options.append(promptOption("Choose your university"));
    for (const name of names) {
      const option = document.createElement("option");
      option.value = name;
      option.textContent = name;
      options.append(option);
    }
    select.replaceChildren(options);
    select.value = names.includes(selected) ? selected : "";
    select.disabled = false;
  } catch {
    select.replaceChildren(promptOption("Universities unavailable"));
    throw new Error("University list could not be loaded. Please reload and try again.");
  }
}
