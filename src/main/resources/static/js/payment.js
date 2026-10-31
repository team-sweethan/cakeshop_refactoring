(function () {
  "use strict";

  const POLL_INTERVAL_MS = 3000;

  document.addEventListener("DOMContentLoaded", function () {
    const root = document.getElementById("payment-root");
    const button = document.getElementById("request-payment");
    const agreement = document.getElementById("payment-agreement");
    const errorNode = document.getElementById("payment-error");
    const modal = document.getElementById("payment-modal");
    if (!root || !button || !modal) return;

    const choice = document.getElementById("payment-gateway-choice");
    const modalError = document.getElementById("payment-modal-error");
    const tossForm = document.getElementById("choose-toss-form");
    const donWorryForm = document.getElementById("choose-donworry-form");
    const donWorryPanel = document.getElementById("donworry-panel");
    // 돈워리 결제 요청 뒤 결과가 나올 때까지 모달을 닫지 못하게 한다.
    let locked = false;

    function showError(node, message) {
      if (!node) return;
      node.textContent = message;
      node.hidden = !message;
    }

    function optionalCustomerEmail(value) {
      const email = (value || "").trim();
      return email && email.length <= 100 ? email : undefined;
    }

    function optionalCustomerMobilePhone(value) {
      const phone = (value || "").replace(/[^0-9]/g, "");
      return phone.length >= 8 && phone.length <= 15 ? phone : undefined;
    }

    function showChoice() {
      locked = false;
      choice.hidden = false;
      donWorryPanel.hidden = true;
      donWorryPanel.replaceChildren();
      showError(modalError, "");
      modal.querySelectorAll(".pg-choice__button").forEach(function (node) {
        node.disabled = node.dataset.unavailable === "true";
      });
    }

    function closeModal() {
      if (locked) return;
      modal.close();
    }

    // 선택한 PG를 서버에 저장한다. 폼의 CSRF 값을 그대로 보낸다.
    async function saveSelection(form) {
      const response = await fetch(form.action, {
        method: "POST",
        body: new FormData(form),
        credentials: "same-origin"
      });
      return response.ok;
    }

    modal.querySelectorAll(".pg-choice__button").forEach(function (node) {
      node.dataset.unavailable = String(node.disabled);
    });

    // 결제하기: 동의를 확인한 뒤 결제 수단 선택 모달을 연다.
    button.addEventListener("click", function () {
      if (!agreement || !agreement.checked) {
        agreement.reportValidity();
        return;
      }
      showChoice();
      modal.showModal();
    });

    modal.addEventListener("click", function (event) {
      if (event.target.closest("[data-modal-close]")) closeModal();
    });

    // Esc로 닫을 때도 결제 진행 중이면 막는다.
    modal.addEventListener("cancel", function (event) {
      if (locked) event.preventDefault();
    });

    if (tossForm) {
      // 토스: 선택을 저장하고 모달을 닫은 뒤 Toss 결제창을 연다.
      tossForm.addEventListener("submit", async function (event) {
        event.preventDefault();
        const tossButton = tossForm.querySelector("button");
        if (!root.dataset.clientKey || typeof TossPayments !== "function") {
          showError(modalError, "결제 모듈을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
          return;
        }

        const amount = Number(root.dataset.paymentAmount);
        if (!Number.isSafeInteger(amount) || amount <= 0) {
          showError(modalError, "결제 금액을 확인할 수 없습니다.");
          return;
        }

        tossButton.disabled = true;
        showError(modalError, "");

        try {
          if (!(await saveSelection(tossForm))) {
            tossButton.disabled = false;
            showError(modalError, "결제 수단을 바꿀 수 없습니다. 이미 진행 중인 결제가 있는지 확인해 주세요.");
            return;
          }
          modal.close();

          const tossPayments = TossPayments(root.dataset.clientKey);
          const payment = tossPayments.payment({
            customerKey: TossPayments.ANONYMOUS
          });
          const request = {
            method: "CARD",
            amount: {
              currency: "KRW",
              value: amount
            },
            orderId: root.dataset.gatewayOrderId,
            orderName: root.dataset.orderName,
            customerName: root.dataset.customerName,
            successUrl: new URL(root.dataset.successUrl, location.origin).href,
            failUrl: new URL(root.dataset.failUrl, location.origin).href
          };
          const customerEmail = optionalCustomerEmail(root.dataset.customerEmail);
          const customerMobilePhone = optionalCustomerMobilePhone(root.dataset.customerPhone);
          if (customerEmail) request.customerEmail = customerEmail;
          if (customerMobilePhone) request.customerMobilePhone = customerMobilePhone;
          // 토스 호출.
          await payment.requestPayment(request);
        } catch (error) {
          tossButton.disabled = false;
          showError(errorNode, "결제창을 열지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
      });
    }

    if (donWorryForm) {
      // 돈워리: 선택을 저장하고 모달 안에 돈워리 결제창을 그린다.
      donWorryForm.addEventListener("submit", async function (event) {
        event.preventDefault();
        const donWorryButton = donWorryForm.querySelector("button");
        donWorryButton.disabled = true;
        showError(modalError, "");
        try {
          if (!(await saveSelection(donWorryForm))) {
            throw new Error("selection");
          }
          const response = await fetch(modal.dataset.donworryPanelUrl, { credentials: "same-origin" });
          if (!response.ok) {
            throw new Error("panel");
          }
          donWorryPanel.innerHTML = await response.text();
          const payForm = donWorryPanel.querySelector("#dw-pay-form");
          if (!payForm) {
            throw new Error("panel");
          }
          openDonWorryPanel(payForm);
        } catch (error) {
          donWorryButton.disabled = false;
          showError(modalError, "돈워리 결제창을 열지 못했습니다. 이미 진행 중인 결제가 있는지 확인해 주세요.");
        }
      });
    }

    function openDonWorryPanel(payForm) {
      choice.hidden = true;
      donWorryPanel.hidden = false;
      const payButton = payForm.querySelector("#dw-pay-button");
      const processing = payForm.querySelector("#dw-processing");
      const cancel = donWorryPanel.querySelector("#dw-cancel");
      if (payButton) payButton.focus();

      // 모달 안의 결제 취소는 결제 수단 선택으로 돌아간다.
      if (cancel) {
        cancel.addEventListener("click", function (event) {
          event.preventDefault();
          showChoice();
        });
      }

      payForm.addEventListener("submit", async function (event) {
        event.preventDefault();
        locked = true;
        payButton.disabled = true;
        payButton.hidden = true;
        if (cancel) cancel.hidden = true;
        processing.hidden = false;
        try {
          const response = await fetch(modal.dataset.donworryRequestUrl, {
            method: "POST",
            body: new FormData(payForm),
            credentials: "same-origin",
            headers: { Accept: "application/json" }
          });
          if (!response.ok) {
            throw new Error("request");
          }
          follow(await response.json(), payForm);
        } catch (error) {
          locked = false;
          payButton.disabled = false;
          payButton.hidden = false;
          if (cancel) cancel.hidden = false;
          processing.hidden = true;
          showError(errorNode, "돈워리 결제를 요청하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
      });
    }

    // 결과가 나오면 기존 성공·실패 화면으로 간다. PENDING·HELD는 모달에서 기다리며 다시 조회한다.
    function follow(progress, payForm) {
      if (!progress.inProgress) {
        location.assign(progress.nextUrl);
        return;
      }
      showWaiting(progress, payForm);
      window.setTimeout(async function () {
        try {
          const response = await fetch(progress.nextUrl + "/progress", {
            credentials: "same-origin",
            headers: { Accept: "application/json" }
          });
          if (!response.ok) throw new Error("progress");
          follow(await response.json(), payForm);
        } catch (error) {
          // 조회에 실패하면 결과 대기 화면으로 넘겨 계속 확인하게 한다.
          location.assign(progress.nextUrl);
        }
      }, POLL_INTERVAL_MS);
    }

    function showWaiting(progress, payForm) {
      const result = donWorryPanel.querySelector("#dw-result");
      if (!result) return;
      payForm.hidden = true;
      result.hidden = false;
      const held = progress.paymentStatus === "HELD";
      donWorryPanel.querySelector("#dw-result-icon").hidden = !held;
      donWorryPanel.querySelector("#dw-result-spin").hidden = held;
      donWorryPanel.querySelector("#dw-result-title").textContent =
        held ? "결제를 확인하고 있습니다" : "결제를 처리하고 있습니다";
      donWorryPanel.querySelector("#dw-result-message").textContent =
        held
          ? "카드 승인은 완료되었고, 안전 확인 후 최종 처리됩니다. 확인이 끝나면 자동으로 결과 화면으로 이동합니다."
          : "잠시 후 결과로 바뀝니다.";
    }
  });
})();
