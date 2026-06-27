export interface AppConfig {
  baseUrl: string;
  loginUrl: string;
  timetableUrl: string;
  examQueryUrl: string;
  examListUrl: string;
  scoreUrl: string;
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

export interface ScoreRecord {
  index: number;
  semester: string;
  courseCode: string;
  courseName: string;
  score: string;
  scoreIdentifier: string;
  credits: string;
  totalHours: string;
  assessmentMethod: string;
  courseAttribute: string;
  courseNature: string;
  isHighlighted: boolean;
  rawText: string;
}
