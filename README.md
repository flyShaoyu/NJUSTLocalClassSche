# ClassSche

Node.js + TypeScript + Playwright project for opening the school system, reusing a saved login session when available, falling back to manual login when needed, and saving the timetable HTML.

## Setup

1. Install Node.js 20+.
2. Copy `.env.example` to `.env`.
3. Set `LOGIN_URL` and `TIMETABLE_URL` if your school system uses different entry points.
4. Optionally set `LOGIN_SUCCESS_SELECTOR` if the site uses a custom post-login marker.
5. Install dependencies:

```bash
npm install
npx playwright install
```

## Run

```bash
npm run start
```

## Output

- `artifacts/session.json`
- `artifacts/storageState.json`
- `artifacts/timetable.html`
- `artifacts/timetable.json`

## Notes

- `.gitignore` excludes `.env` and `artifacts/`, so local session state and outputs stay out of git.
- On the first run, or after the login state expires, the script opens `LOGIN_URL` in a visible browser and waits for you to complete login manually.
- After manual login succeeds, the script saves `artifacts/storageState.json` and reuses it on later runs until the session expires.
- The parser is heuristic-based because timetable HTML layouts vary across school systems.
