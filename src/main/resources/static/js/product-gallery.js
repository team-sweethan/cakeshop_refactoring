(function () {
    "use strict";

    // 상품 상세 사진. 아래 앨범 띠(대표 이미지 포함)를 누르거나 좌우 버튼으로 넘기고,
    // 가만히 두면 AUTO_ADVANCE_MS마다 다음 사진으로 서서히 바뀐다.
    // 마우스를 올리거나 키보드 포커스가 사진 영역 안에 있으면 멈춘다. 움직임 줄이기 설정이면 자동으로 넘기지 않는다.
    const AUTO_ADVANCE_MS = 3000;
    const FADE_MS = 350;

    document.addEventListener("DOMContentLoaded", function () {
        const gallery = document.querySelector(".product-gallery");
        if (!gallery) return;

        const mainImage = gallery.querySelector("[data-product-main-image]");
        if (!mainImage) return;

        const thumbnailButtons = Array.from(
            gallery.querySelectorAll("[data-product-thumbnail]")
        );
        const previousButton = gallery.querySelector(
            "[data-product-gallery-previous]"
        );
        const nextButton = gallery.querySelector(
            "[data-product-gallery-next]"
        );
        // 앨범 띠가 있으면 띠 순서가 곧 사진 순서다. 띠가 없으면 사진이 한 장이다.
        const images = thumbnailButtons.length > 0
            ? thumbnailButtons.map(function (button) {
                return {
                    src: button.dataset.imageUrl,
                    alt: button.dataset.imageAlt
                };
            })
            : [{ src: mainImage.src, alt: mainImage.alt }];
        if (images.length < 2) return;

        // 다음 사진이 바뀔 때 깜빡이지 않게 미리 받아 둔다.
        images.forEach(function (image) {
            const preload = new Image();
            preload.src = image.src;
        });

        const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        let currentIndex = 0;
        let fadeTimer = null;
        let autoTimer = null;
        let hovered = false;
        let focused = false;

        function markActive() {
            thumbnailButtons.forEach(function (button, thumbnailIndex) {
                const active = thumbnailIndex === currentIndex;
                button.classList.toggle("is-active", active);
                button.setAttribute("aria-current", active ? "true" : "false");
            });
        }

        function showImage(index) {
            const nextIndex = (index + images.length) % images.length;
            if (nextIndex === currentIndex) return;
            currentIndex = nextIndex;
            markActive();

            window.clearTimeout(fadeTimer);
            if (reduceMotion) {
                mainImage.src = images[currentIndex].src;
                mainImage.alt = images[currentIndex].alt;
                return;
            }
            // 흐려졌다가 새 사진으로 다시 선명해진다.
            mainImage.classList.add("is-fading");
            fadeTimer = window.setTimeout(function () {
                mainImage.src = images[currentIndex].src;
                mainImage.alt = images[currentIndex].alt;
                mainImage.classList.remove("is-fading");
            }, FADE_MS);
        }

        function canAutoAdvance() {
            return !reduceMotion && !hovered && !focused && !document.hidden;
        }

        // 사람이 넘긴 직후에는 3초를 처음부터 다시 센다.
        function restartAutoAdvance() {
            window.clearInterval(autoTimer);
            if (reduceMotion) return;
            autoTimer = window.setInterval(function () {
                if (canAutoAdvance()) showImage(currentIndex + 1);
            }, AUTO_ADVANCE_MS);
        }

        thumbnailButtons.forEach(function (button, index) {
            button.addEventListener("click", function () {
                showImage(index);
                restartAutoAdvance();
            });
        });

        if (previousButton) {
            previousButton.addEventListener("click", function () {
                showImage(currentIndex - 1);
                restartAutoAdvance();
            });
        }

        if (nextButton) {
            nextButton.addEventListener("click", function () {
                showImage(currentIndex + 1);
                restartAutoAdvance();
            });
        }

        gallery.addEventListener("mouseenter", function () { hovered = true; });
        gallery.addEventListener("mouseleave", function () { hovered = false; });
        gallery.addEventListener("focusin", function () { focused = true; });
        gallery.addEventListener("focusout", function (event) {
            focused = gallery.contains(event.relatedTarget);
        });

        markActive();
        restartAutoAdvance();
    });
})();
