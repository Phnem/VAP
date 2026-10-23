(function () {
  var page = document.querySelector(".page");
  var buttons = document.querySelectorAll("[data-mask]");
  buttons.forEach(function (b) {
    b.addEventListener("click", function () {
      page.style.setProperty("--r", b.getAttribute("data-mask"));
      buttons.forEach(function (o) { o.setAttribute("aria-pressed", o === b ? "true" : "false"); });
    });
  });
  var g = document.getElementById("guides-toggle");
  if (g) g.addEventListener("change", function () { page.classList.toggle("show-guides", g.checked); });
})();
