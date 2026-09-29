export const TEACHING_ORIGIN = "https://bkjw.njust.edu.cn";
export const TEACHING_SSO_URL = `${TEACHING_ORIGIN}/njlgdx/indexsso.jsp`;
export const DEFAULT_LOGIN_URL =
  "https://ids.njust.edu.cn/authserver/login?service=https%3A%2F%2Fehall2.njust.edu.cn%2Flogin";

// Existing .env files still contain the retired IP endpoints. Keep explicit
// custom endpoints, but migrate those known school addresses automatically.
export const resolveEndpoint = (value: string | undefined, fallback: string): string => {
  if (!value?.trim()) return fallback;
  const url = new URL(value.trim());
  if (["202.119.81.112", "202.119.81.113"].includes(url.hostname)) {
    if (url.port === "8080") return fallback;
    if (url.port === "9080") return `${TEACHING_ORIGIN}${url.pathname}${url.search}`;
  }
  return value.trim();
};
