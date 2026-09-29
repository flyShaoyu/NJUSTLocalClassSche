export const errorMessage = (error: unknown): string =>
  error instanceof Error ? error.message : String(error);

export const describeRequestFailure = (step: string, url: string, error: unknown): Error => {
  const detail = errorMessage(error);
  if ((error instanceof Error && error.name === "TimeoutError") || /timed[ _-]?out|timeout|ETIMEDOUT/i.test(detail)) {
    return new Error(`${step}失败：网页连接超时（${url}）。请检查网络或稍后重试。原始错误：${detail}`, { cause: error });
  }
  if (/ERR_NAME_NOT_RESOLVED|ENOTFOUND|EAI_AGAIN/i.test(detail)) {
    return new Error(`${step}失败：无法解析网站域名（${url}）。请检查网络或学校网站地址。原始错误：${detail}`, { cause: error });
  }
  return new Error(`${step}失败：无法连接 ${url}。原始错误：${detail}`, { cause: error });
};

export const withStep = async <T>(step: string, action: () => Promise<T> | T): Promise<T> => {
  try {
    return await action();
  } catch (error) {
    throw new Error(`${step}失败：${errorMessage(error)}`, { cause: error });
  }
};

export const parseJsonStep = <T>(step: string, filePath: string, content: string): T => {
  try {
    return JSON.parse(content) as T;
  } catch (error) {
    throw new Error(`${step}解析错误：${filePath}。${errorMessage(error)}`, { cause: error });
  }
};
