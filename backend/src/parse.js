// Parser MẪU — CHƯA xác nhận bằng dữ liệu thật từ MBBank hay bất kỳ
// nguồn nào khác. Đây chỉ là điểm khởi đầu để bạn chỉnh lại ngay khi có
// mẫu thật (xem README phần "Lấy mẫu thật từ điện thoại").
//
// combined = title + text + big_text + sub_text + lines nối lại, vì
// chưa biết chắc trường nào chứa số tiền/mã đơn thật sự trên máy bạn.

const AMOUNT_RE = /([+-])\s*([\d.,]+)\s*(?:VND|VNĐ|đ)\b/i;
const ORDER_RE = /\bDH\d+\b/i;

/**
 * Trả về { type, amount, order_code } nếu tìm thấy số tiền hợp lệ,
 * hoặc null nếu không khớp gì (không phải giao dịch, hoặc định dạng
 * khác với regex hiện tại).
 */
export function parseTransaction(combined) {
  const amountMatch = AMOUNT_RE.exec(combined);
  if (!amountMatch) return null;

  const sign = amountMatch[1];
  const amount = Number(amountMatch[2].replace(/[.,]/g, ""));
  if (!Number.isFinite(amount)) return null;

  const orderMatch = ORDER_RE.exec(combined);

  return {
    type: sign === "+" ? "credit" : "debit",
    amount,
    order_code: orderMatch ? orderMatch[0].toUpperCase() : null,
  };
}
