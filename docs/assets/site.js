(() => {
  const root = document.documentElement;
  root.classList.add("js");

  const nav = document.getElementById("site-nav");
  const navToggle = document.querySelector(".nav-toggle");
  const navLinks = nav ? nav.querySelectorAll("a") : [];

  const setNav = (open) => {
    if (!nav || !navToggle) return;
    nav.classList.toggle("is-open", open);
    navToggle.setAttribute("aria-expanded", String(open));
    navToggle.setAttribute("aria-label", open ? "Закрыть меню" : "Открыть меню");
  };

  if (navToggle && nav) {
    navToggle.addEventListener("click", () => {
      setNav(navToggle.getAttribute("aria-expanded") !== "true");
    });

    navLinks.forEach((link) => {
      link.addEventListener("click", () => setNav(false));
    });

    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape") setNav(false);
    });

    document.addEventListener("click", (event) => {
      if (navToggle.getAttribute("aria-expanded") !== "true") return;
      if (nav.contains(event.target) || navToggle.contains(event.target)) return;
      setNav(false);
    });

    window.addEventListener("resize", () => {
      if (window.innerWidth > 840) setNav(false);
    });
  }

  const copyStatus = document.getElementById("copy-status");

  const copyText = async (text) => {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return;
    }

    const area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.top = "-1000px";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    const copied = document.execCommand("copy");
    area.remove();
    if (!copied) throw new Error("copy failed");
  };

  document.querySelectorAll("[data-copy]").forEach((button) => {
    button.addEventListener("click", async () => {
      const value = button.getAttribute("data-copy");
      const label = button.querySelector(".copy-label");
      const original = label ? label.textContent : "";

      try {
        await copyText(value);
        if (label) label.textContent = "Скопировано";
        if (copyStatus) copyStatus.textContent = "SHA-256 скопирован в буфер обмена.";
      } catch {
        if (label) label.textContent = "Выделите вручную";
        if (copyStatus) copyStatus.textContent = "Не удалось скопировать автоматически. Выделите хеш вручную.";
      }

      window.setTimeout(() => {
        if (label) label.textContent = original;
        if (copyStatus) copyStatus.textContent = "";
      }, 2400);
    });
  });

  const year = document.getElementById("year");
  if (year) year.textContent = String(new Date().getFullYear());

  const reveals = document.querySelectorAll(".reveal");
  if (!reveals.length) return;

  if (!("IntersectionObserver" in window)) {
    reveals.forEach((element) => element.classList.add("is-visible"));
    return;
  }

  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add("is-visible");
        observer.unobserve(entry.target);
      });
    },
    { threshold: 0.12, rootMargin: "0px 0px -40px" },
  );

  reveals.forEach((element) => observer.observe(element));
})();
