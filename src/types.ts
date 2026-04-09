export interface AppConfig {
  baseUrl: string;
  loginUrl: string;
  timetableUrl: string;
  username?: string;
  password?: string;
  headless: boolean;
  loginSuccessSelector?: string;
  manualLoginTimeoutMs: number;
}

export interface TimetableCourse {
  courseName: string;
  weekday: string;
  periods: string;
  classroom: string;
  weeks: string;
  teacher?: string;
  courseCode?: string;
  courseSequence?: string;
  courseType?: string;
  rawText: string;
}
