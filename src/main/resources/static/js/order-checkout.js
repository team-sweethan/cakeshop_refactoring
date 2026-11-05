(function () {
  "use strict";

  function initializePickupScheduler() {
    const scheduler = document.querySelector("[data-pickup-scheduler]");
    if (!scheduler) return;

    // 날짜는 선택 상자(장바구니 주문서) 또는 가로 날짜 버튼(상품 주문서)으로 고른다.
    const dateSelect = scheduler.querySelector("[data-pickup-date]");
    const dateButtons = Array.from(
      scheduler.querySelectorAll("[data-pickup-date-option]")
    );
    const panels = Array.from(
      scheduler.querySelectorAll("[data-pickup-time-panel]")
    );
    if ((!dateSelect && !dateButtons.length) || !panels.length) return;

    function markDateButtons(date) {
      dateButtons.forEach(function (button) {
        const active = button.dataset.pickupDateOption === date;
        button.classList.toggle("is-active", active);
        button.setAttribute("aria-pressed", active ? "true" : "false");
      });
    }

    function showDate(date, clearSelectedTime) {
      panels.forEach(function (panel) {
        panel.hidden = panel.dataset.pickupTimePanel !== date;
        panel.querySelectorAll("[name='pickupAt']").forEach(function (input) {
          input.required = false;
        });
      });
      const visiblePanel = panels.find(function (panel) {
        return panel.dataset.pickupTimePanel === date;
      });
      const firstVisibleTime = visiblePanel
        ? visiblePanel.querySelector("[name='pickupAt']")
        : null;
      if (firstVisibleTime) firstVisibleTime.required = true;
      if (clearSelectedTime) {
        const selectedTime = scheduler.querySelector("[name='pickupAt']:checked");
        if (selectedTime) selectedTime.checked = false;
      }
    }

    const selectedTime = scheduler.querySelector("[name='pickupAt']:checked");
    const firstDate = dateSelect
      ? dateSelect.value
      : dateButtons[0].dataset.pickupDateOption;
    const initialDate = selectedTime
      ? selectedTime.value.substring(0, 10)
      : firstDate;
    if (dateSelect) dateSelect.value = initialDate;
    markDateButtons(initialDate);
    showDate(initialDate, false);

    if (dateSelect) {
      dateSelect.addEventListener("change", function () {
        showDate(dateSelect.value, true);
      });
    }
    dateButtons.forEach(function (button) {
      button.addEventListener("click", function () {
        if (button.getAttribute("aria-pressed") === "true") return;
        const date = button.dataset.pickupDateOption;
        markDateButtons(date);
        showDate(date, true);
      });
    });
  }

  function initializeOrdererContact() {
    const sameAsOrderer = document.querySelector("[data-same-as-orderer]");
    const ordererName = document.getElementById("ordererName");
    const ordererPhone = document.getElementById("ordererPhone");
    const pickupName = document.getElementById("pickupName");
    const pickupPhone = document.getElementById("pickupPhone");
    if (!sameAsOrderer || !ordererName || !ordererPhone
        || !pickupName || !pickupPhone) {
      return;
    }

    function copyOrdererContact() {
      if (!sameAsOrderer.checked) return;
      pickupName.value = ordererName.value;
      pickupPhone.value = ordererPhone.value;
    }

    // 상품 주문서는 같으면 픽업자 칸을 접는다. 서버가 픽업자 칸에 오류를 돌려줬으면 계속 펼쳐 둔다.
    // 장바구니 주문서처럼 접을 영역이 없으면 지금처럼 읽기 전용으로만 바꾼다.
    const pickupContact = document.querySelector("[data-pickup-contact]");
    const pickupHasError = Boolean(
      pickupContact && pickupContact.querySelector(".form-error")
    );

    function updatePickupContactMode() {
      pickupName.readOnly = sameAsOrderer.checked;
      pickupPhone.readOnly = sameAsOrderer.checked;
      if (pickupContact) {
        pickupContact.hidden = sameAsOrderer.checked && !pickupHasError;
        sameAsOrderer.setAttribute("aria-expanded", String(!pickupContact.hidden));
      }
      copyOrdererContact();
    }

    sameAsOrderer.addEventListener("change", updatePickupContactMode);
    ordererName.addEventListener("input", copyOrdererContact);
    ordererPhone.addEventListener("input", copyOrdererContact);
    updatePickupContactMode();
  }

  function initializeSingleSubmit() {
    const form = document.querySelector(
      "form[action$='/orders/general'], form[action$='/orders/general/cart']"
    );
    if (!form) return;

    form.addEventListener("submit", function () {
      if (form.dataset.submitting === "true") return;
      form.dataset.submitting = "true";
      const submitButton = form.querySelector("button[type='submit']");
      if (submitButton) {
        submitButton.disabled = true;
        submitButton.textContent = "주문 생성 중...";
      }
    });
  }

  function initializeCouponAmountDisplay() {
    const select = document.getElementById("memberCouponId");
    const total = document.querySelector("[data-order-total]");
    const discount = document.querySelector("[data-coupon-discount]");
    const finalAmount = document.querySelector("[data-final-amount]");
    if (!select || !total || !discount || !finalAmount) return;

    const originalAmount = Number(total.dataset.originalAmount);
    const format = value => Number(value).toLocaleString("ko-KR") + "원";

    function updateAmountDisplay() {
      const selectedCoupon = select.selectedOptions[0];
      if (!selectedCoupon || !selectedCoupon.dataset.discountType) {
        discount.textContent = "0원";
        finalAmount.textContent = format(originalAmount);
        return;
      }

      const discountValue = Number(selectedCoupon.dataset.discountValue);
      const maximumDiscountAmount = selectedCoupon.dataset.maximumDiscountAmount
        ? Number(selectedCoupon.dataset.maximumDiscountAmount)
        : null;
      let discountAmount = selectedCoupon.dataset.discountType === "PERCENTAGE"
        ? Math.floor(originalAmount * discountValue / 100)
        : Math.floor(discountValue);
      if (maximumDiscountAmount !== null) {
        discountAmount = Math.min(discountAmount, maximumDiscountAmount);
      }
      discountAmount = Math.min(discountAmount, originalAmount);

      discount.textContent = "-" + format(discountAmount);
      finalAmount.textContent = format(originalAmount - discountAmount);
    }

    select.addEventListener("change", updateAmountDisplay);
    updateAmountDisplay();
  }

  document.addEventListener("DOMContentLoaded", function () {
    initializePickupScheduler();
    initializeOrdererContact();
    initializeSingleSubmit();
    initializeCouponAmountDisplay();
  });
})();
