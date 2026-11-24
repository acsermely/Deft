window.__deftFetch = "pending";
fetch("https://example.com/x")
  .then(function () {
    window.__deftFetch = "ok";
  })
  .catch(function (e) {
    window.__deftFetch = "blocked:" + (e && e.name);
  });
