export interface AppConfig {
  baseUrl: string;
  loginUrl: string;
  timetableUrl: string;
  examQueryUrl: string;
  examListUrl: string;
  username?: string;
  password?: string;
  semester?: string;
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

export interface ExamArrangement {
  index: number;
  examSession: string;
  courseCode: string;
  courseName: string;
  examTime: string;
  examRoom: string;
  seatNumber: string;
  teacher?: string;
  rawText: string;
}
