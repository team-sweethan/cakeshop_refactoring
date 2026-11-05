(function () {
  "use strict";

  // 장바구니 · 주문 제작 주문서: 날짜 선택 상자를 바꾸면 그 날짜의 시각 버튼만 보인다.
  function initializePickupScheduler() {
    const scheduler = document.querySelector("[data-pickup-scheduler]");
    if (!scheduler) return;

    const dateSelect = scheduler.querySelector("[data-pickup-date]");
    const panels = Array.from(
      scheduler.querySelectorAll("[data-pickup-time-panel]")
    );
    if (!dateSelect || !panels.length) return;

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
    const initialDate = selectedTime
      ? selectedTime.value.substring(0, 10)
      : dateSelect.value;
    dateSelect.value = initialDate;
    showDate(initialDate, false);
    dateSelect.addEventListener("change", function () {
      showDate(dateSelect.value, true);
    });
  }

  // 상품 주문서: 날짜는 달력, 시간은 시 → 분 버튼으로 고른다.
  // 서버가 그린 픽업 시각 라디오(data-pickup-time-panel 안의 name=pickupAt)가 가능한 시각의 전부이자 실제로 보내는 값이다.
  // 이 함수는 그 라디오를 읽어 달력과 버튼을 그리고, 고르면 해당 라디오를 체크한다.
  function initializePickupPicker() {
    const picker = document.querySelector("[data-pickup-picker]");
    if (!picker) return;

    const ui = picker.querySelector("[data-pickup-picker-ui]");
    const calendar = picker.querySelector("[data-pickup-calendar]");
    const hoursBox = picker.querySelector("[data-pickup-hours]");
    const minutesBox = picker.querySelector("[data-pickup-minutes]");
    const summary = picker.querySelector("[data-pickup-summary]");
    const error = picker.querySelector("[data-pickup-picker-error]");
    const panels = Array.from(picker.querySelectorAll("[data-pickup-time-panel]"));
    if (!ui || !calendar || !hoursBox || !minutesBox || !panels.length) return;

    // 날짜(yyyy-MM-dd) → { label, slots: [{ hour, minute, input }] }
    const dates = new Map();
    panels.forEach(function (panel) {
      const slots = Array.from(panel.querySelectorAll("[name='pickupAt']")).map(function (input) {
        // 값은 2026-11-05T14:30 모양이다.
        return { hour: input.value.substring(11, 13), minute: input.value.substring(14, 16), input: input };
      });
      if (slots.length) {
        dates.set(panel.dataset.pickupTimePanel, { label: panel.dataset.pickupDateLabel, slots: slots });
      }
    });
    if (!dates.size) return;

    const dateKeys = Array.from(dates.keys()).sort();
    // 시 버튼은 모든 날짜의 시를 합쳐 고정해 두고, 고른 날짜에 없는 시는 흐리게 막는다.
    const allHours = Array.from(new Set(
      Array.from(dates.values()).flatMap(function (day) {
        return day.slots.map(function (slot) { return slot.hour; });
      })
    )).sort();
    const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

    const checked = picker.querySelector("[name='pickupAt']:checked");
    let selectedDate = checked ? checked.value.substring(0, 10) : dateKeys[0];
    let selectedHour = checked ? checked.value.substring(11, 13) : null;
    let selectedMinute = checked ? checked.value.substring(14, 16) : null;
    let viewMonth = selectedDate.substring(0, 7);

    function pad(value) { return String(value).padStart(2, "0"); }
    function monthOf(key) { return key.substring(0, 7); }
    function shiftMonth(month, delta) {
      const year = Number(month.substring(0, 4));
      const index = Number(month.substring(5, 7)) - 1 + delta;
      const date = new Date(year, index, 1);
      return date.getFullYear() + "-" + pad(date.getMonth() + 1);
    }
    // key: 다시 그린 뒤 같은 버튼을 찾아 포커스를 되돌리는 표식(nav:prev, day:2026-11-05, hour:14, minute:30)
    function button(className, text, key) {
      const element = document.createElement("button");
      element.type = "button";
      element.className = className;
      element.textContent = text;
      element.dataset.pickerKey = key;
      return element;
    }

    // 다시 그리면 버튼이 새로 만들어져 키보드 포커스가 사라진다(#48 Codex 리뷰).
    // 그리기 전에 포커스가 있던 버튼의 표식을 기억했다가, 같은 표식의 새 버튼으로 포커스를 되돌린다.
    // 같은 버튼이 없거나 막혔으면(예: 날짜를 바꿔 그 시가 없음) 같은 종류의 첫 번째 누를 수 있는 버튼으로 옮긴다.
    function keepFocus(draw) {
      const focused = document.activeElement;
      const key = focused && picker.contains(focused) ? focused.dataset.pickerKey : null;
      draw();
      if (!key) return;
      const same = picker.querySelector("[data-picker-key='" + key + "']");
      if (same && !same.disabled) {
        same.focus();
        return;
      }
      const kind = key.split(":")[0];
      const sibling = Array.from(picker.querySelectorAll("[data-picker-key^='" + kind + ":']"))
        .find(function (element) { return !element.disabled; });
      if (sibling) sibling.focus();
    }

    function slotsOf(date) { return dates.get(date) ? dates.get(date).slots : []; }

    function selectedSlot() {
      if (!selectedHour || !selectedMinute) return null;
      return slotsOf(selectedDate).find(function (slot) {
        return slot.hour === selectedHour && slot.minute === selectedMinute;
      }) || null;
    }

    // 주문 요약의 "픽업 일시" 줄과 결제하기 아래 안내, 그리고 이 카드(빨간 테두리 대상)
    const summaryValue = document.querySelector("[data-pickup-summary-value]");
    const submitError = document.querySelector("[data-pickup-submit-error]");
    const card = picker.closest(".checkout-card");

    function showMissingPickup(missing) {
      if (error) error.hidden = !missing;
      if (submitError) submitError.hidden = !missing;
      if (card) card.classList.toggle("is-invalid", missing);
    }

    function syncRadio() {
      picker.querySelectorAll("[name='pickupAt']").forEach(function (input) { input.checked = false; });
      const slot = selectedSlot();
      if (slot) slot.input.checked = true;
      const text = slot ? dates.get(selectedDate).label + " " + slot.hour + ":" + slot.minute : "";
      if (summary) {
        summary.hidden = !slot;
        summary.textContent = slot ? "선택한 픽업 일시: " + text : "";
      }
      if (summaryValue) {
        summaryValue.textContent = slot ? text : "아직 선택 안 함";
        summaryValue.classList.toggle("is-empty", !slot);
      }
      // 다 고르면 안내와 강조를 바로 거둔다.
      if (slot) showMissingPickup(false);
    }

    function renderCalendar() {
      calendar.replaceChildren();
      const head = document.createElement("div");
      head.className = "checkout-calendar__head";
      const title = document.createElement("strong");
      title.textContent = viewMonth.substring(0, 4) + "년 " + Number(viewMonth.substring(5, 7)) + "월";
      const previous = button("checkout-calendar__nav", "‹", "nav:prev");
      const next = button("checkout-calendar__nav", "›", "nav:next");
      previous.setAttribute("aria-label", "이전 달");
      next.setAttribute("aria-label", "다음 달");
      previous.disabled = viewMonth <= monthOf(dateKeys[0]);
      next.disabled = viewMonth >= monthOf(dateKeys[dateKeys.length - 1]);
      previous.addEventListener("click", function () {
        viewMonth = shiftMonth(viewMonth, -1);
        keepFocus(renderCalendar);
      });
      next.addEventListener("click", function () {
        viewMonth = shiftMonth(viewMonth, 1);
        keepFocus(renderCalendar);
      });
      head.append(title, previous, next);

      const grid = document.createElement("div");
      grid.className = "checkout-calendar__grid";
      grid.setAttribute("role", "group");
      grid.setAttribute("aria-label", title.textContent + " 픽업 날짜");
      WEEKDAYS.forEach(function (weekday) {
        const cell = document.createElement("span");
        cell.className = "checkout-calendar__weekday";
        cell.textContent = weekday;
        cell.setAttribute("aria-hidden", "true");
        grid.append(cell);
      });

      const year = Number(viewMonth.substring(0, 4));
      const month = Number(viewMonth.substring(5, 7));
      const leading = new Date(year, month - 1, 1).getDay();
      const lastDay = new Date(year, month, 0).getDate();
      for (let i = 0; i < leading; i++) {
        grid.append(document.createElement("span"));
      }
      for (let day = 1; day <= lastDay; day++) {
        const key = viewMonth + "-" + pad(day);
        const available = dates.has(key);
        if (!available) {
          const cell = document.createElement("span");
          cell.className = "checkout-calendar__day is-unavailable";
          cell.textContent = String(day);
          cell.setAttribute("aria-hidden", "true");
          grid.append(cell);
          continue;
        }
        const dayButton = button("checkout-calendar__day", String(day), "day:" + key);
        const active = key === selectedDate;
        dayButton.classList.toggle("is-active", active);
        dayButton.setAttribute("aria-pressed", String(active));
        dayButton.setAttribute("aria-label", dates.get(key).label);
        dayButton.addEventListener("click", function () {
          if (key === selectedDate) return;
          selectedDate = key;
          // 날짜가 바뀌면 같은 시 · 분이 그날에도 있을 때만 유지한다.
          if (!selectedSlot()) { selectedHour = null; selectedMinute = null; }
          renderAll();
        });
        grid.append(dayButton);
      }
      calendar.append(head, grid);
    }

    function renderHours() {
      hoursBox.replaceChildren();
      const available = new Set(slotsOf(selectedDate).map(function (slot) { return slot.hour; }));
      allHours.forEach(function (hour) {
        const hourButton = button("checkout-clock__option", String(Number(hour)) + "시", "hour:" + hour);
        const active = hour === selectedHour;
        hourButton.disabled = !available.has(hour);
        hourButton.classList.toggle("is-active", active);
        hourButton.setAttribute("aria-pressed", String(active));
        hourButton.addEventListener("click", function () {
          selectedHour = hour;
          const minutes = slotsOf(selectedDate).filter(function (slot) { return slot.hour === hour; });
          // 그 시에 고를 분이 하나뿐이면 바로 고르고, 아니면 분을 직접 고르게 비워 둔다.
          selectedMinute = minutes.length === 1 ? minutes[0].minute : null;
          renderAll();
        });
        hoursBox.append(hourButton);
      });
    }

    function renderMinutes() {
      minutesBox.replaceChildren();
      if (!selectedHour) {
        const hint = document.createElement("span");
        hint.className = "checkout-clock__hint";
        hint.textContent = "시를 먼저 고르세요.";
        minutesBox.append(hint);
        return;
      }
      slotsOf(selectedDate)
        .filter(function (slot) { return slot.hour === selectedHour; })
        .forEach(function (slot) {
          const minuteButton = button("checkout-clock__option", slot.minute + "분", "minute:" + slot.minute);
          const active = slot.minute === selectedMinute;
          minuteButton.classList.toggle("is-active", active);
          minuteButton.setAttribute("aria-pressed", String(active));
          minuteButton.addEventListener("click", function () {
            selectedMinute = slot.minute;
            renderAll();
          });
          minutesBox.append(minuteButton);
        });
    }

    function renderAll() {
      keepFocus(function () {
        renderCalendar();
        renderHours();
        renderMinutes();
      });
      syncRadio();
    }

    // 고르지 않고 제출하면 막고 안내한다. 숨긴 라디오의 브라우저 기본 안내는 위치가 어색해 쓰지 않는다.
    picker.querySelectorAll("[name='pickupAt']").forEach(function (input) { input.required = false; });
    const form = picker.closest("form");
    if (form) {
      form.addEventListener("submit", function (event) {
        if (selectedSlot()) return;
        event.preventDefault();
        // 결제하기 바로 아래와 픽업 카드 맨 위에 안내를 보이고, 카드로 화면을 옮긴다.
        showMissingPickup(true);
        (card || picker).scrollIntoView({ block: "start", behavior: "smooth" });
      });
    }

    picker.classList.add("checkout-picker-ready");
    ui.hidden = false;
    renderAll();
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

    form.addEventListener("submit", function (event) {
      // 픽업 일시를 고르지 않아 앞에서 막힌 제출이면 버튼을 잠그지 않는다.
      if (event.defaultPrevented) return;
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
    // 픽업 일시 제출 검사를 한 번만 제출 처리보다 먼저 등록한다.
    initializePickupPicker();
    initializeOrdererContact();
    initializeSingleSubmit();
    initializeCouponAmountDisplay();
  });
})();
