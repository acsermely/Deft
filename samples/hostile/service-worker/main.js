window.__deftSw = "none";
try {
  navigator.serviceWorker.register("./sw.js").then(function () {
    window.__deftSw = "registered";
  }).catch(function () {
    window.__deftSw = "rejected";
  });
} catch (e) {
  window.__deftSw = "threw";
}
