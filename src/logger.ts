const formatTime = (): string => new Date().toISOString();

export const logStep = (message: string): void => {
  console.log(`[${formatTime()}] ${message}`);
};

export const logDivider = (title: string): void => {
  logStep(`========== ${title} ==========`);
};
