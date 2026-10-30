const $ = (id) => document.getElementById(id);
let selected = [];

async function init() {
  try {
    const health = await (await fetch("/api/health")).json();
    $("health").textContent = `${health.sdk} ${health.sdkVersion}, ${health.licenseMode} mode`;
    $("health").classList.add("up");
  } catch {
    $("health").textContent = "Service not reachable";
    $("health").classList.add("down");
  }
  const formats = await (await fetch("/api/formats")).json();
  $("accepted").textContent = "Accepted: " + formats.inputs.map((f) => f.extension.toUpperCase()).join(", ");
  for (const output of formats.outputs) {
    $("output").add(new Option(output.label, output.id));
  }
}

function render() {
  const list = $("selected");
  list.replaceChildren();
  selected.forEach((file, index) => {
    const li = document.createElement("li");
    li.textContent = `${file.name} (${size(file.size)})`;
    const remove = document.createElement("button");
    remove.type = "button";
    remove.textContent = "×";
    remove.title = "Remove";
    remove.onclick = () => { selected.splice(index, 1); render(); };
    li.append(remove);
    list.append(li);
  });
  $("convert").disabled = selected.length === 0;
}

function add(files) {
  selected.push(...files);
  render();
}

function size(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function cell(row, content, className) {
  const td = document.createElement("td");
  if (className) td.className = className;
  if (content instanceof Node) td.append(content); else td.textContent = content ?? "";
  row.append(td);
  return td;
}

function link(href, text) {
  const a = document.createElement("a");
  a.href = href;
  a.textContent = text;
  if (text === "View") a.target = "_blank";
  return a;
}

function showResults(job) {
  $("resultPanel").hidden = false;
  const converted = job.results.filter((r) => r.status === "converted").length;
  $("summary").textContent = `${converted} of ${job.results.length} converted to ${job.output} in ${job.totalMs} ms` +
    (job.merge ? ", merged" : "");
  const body = $("results");
  body.replaceChildren();
  for (const r of job.results) {
    const row = document.createElement("tr");
    if (r.status === "converted") {
      const img = document.createElement("img");
      img.className = "thumb";
      img.alt = `First page of ${r.output}`;
      img.src = r.thumbnail;
      cell(row, img);
      cell(row, r.input, "name");
      cell(row, r.detectedType);
      cell(row, r.pages, "num");
      cell(row, `${r.conversionMs} ms`, "num");
      cell(row, size(r.outputBytes), "num");
      if (r.pdfa) {
        cell(row, `${r.pdfa.level} ${r.pdfa.valid ? "valid" : "not valid"}`, r.pdfa.valid ? "ok" : "bad");
      } else {
        cell(row, "-");
      }
      const actions = cell(row, "");
      actions.append(link(r.view, "View"), link(r.download, "Download"));
    } else {
      cell(row, "");
      cell(row, r.input, "name");
      const error = cell(row, r.error.message, "error");
      error.colSpan = 6;
    }
    body.append(row);
  }
}

$("files").addEventListener("change", (e) => { add(e.target.files); e.target.value = ""; });
const drop = $("drop");
drop.addEventListener("dragover", (e) => { e.preventDefault(); drop.classList.add("over"); });
drop.addEventListener("dragleave", () => drop.classList.remove("over"));
drop.addEventListener("drop", (e) => { e.preventDefault(); drop.classList.remove("over"); add(e.dataTransfer.files); });
$("clear").addEventListener("click", () => { selected = []; render(); $("resultPanel").hidden = true; });

$("form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const form = new FormData();
  selected.forEach((file) => form.append("files", file, file.name));
  form.append("output", $("output").value);
  form.append("merge", $("merge").checked);
  $("convert").disabled = true;
  $("convert").textContent = "Converting...";
  try {
    const response = await fetch("/api/jobs", { method: "POST", body: form });
    const json = await response.json();
    if (!response.ok) {
      showResults({ output: $("output").selectedOptions[0].text, totalMs: 0, merge: false,
        results: [{ input: json.file || "Request", status: "failed", error: json }] });
    } else {
      showResults(json);
    }
  } catch (err) {
    alert("The request failed: " + err.message);
  } finally {
    $("convert").disabled = selected.length === 0;
    $("convert").textContent = "Convert";
  }
});

init();
